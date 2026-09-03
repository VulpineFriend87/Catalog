package top.vulpine.catalog.tracking;

import top.vulpine.catalog.jar.model.InstalledJar;
import top.vulpine.catalog.jar.model.ScanResult;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.tracking.model.ReconcileReport;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brings the tracking state in line with what is actually on disk.
 *
 * <p>This is what makes adoption automatic instead of a chore: an operator can add, remove, rename
 * or swap jars by hand between restarts, and Catalog works out what happened by comparing hashes
 * rather than asking to be told.</p>
 *
 * <p>Deliberately free of I/O. The caller does the scanning and the one bulk call to Modrinth and
 * passes both results in, which keeps every branch below testable without a network or a disk.</p>
 */
public final class Reconciler {

    /** Recorded as the installer for plugins adopted by the scan rather than by a person. */
    public static final String AUTO_TRACK = "auto-track";

    private final TrackingStore store;
    private final IgnoreList ignoreList;
    private final TrackingDefaults defaults;
    private final boolean adoptNewPlugins;

    public Reconciler(TrackingStore store, IgnoreList ignoreList, TrackingDefaults defaults) {
        this(store, ignoreList, defaults, true);
    }

    /**
     * @param adoptNewPlugins false to leave recognised but untracked jars alone, which is what an
     *                        operator asks for by turning auto-tracking off. Plugins already
     *                        tracked are still reconciled, since a jar deleted by hand has to be
     *                        noticed either way.
     */
    public Reconciler(TrackingStore store, IgnoreList ignoreList, TrackingDefaults defaults,
                      boolean adoptNewPlugins) {
        this.store = store;
        this.ignoreList = ignoreList;
        this.defaults = defaults;
        this.adoptNewPlugins = adoptNewPlugins;
    }

    /**
     * Reconciles the store against a scan, mutating the store in place.
     *
     * <p>The caller is responsible for saving afterwards, so a run that changed nothing does not
     * rewrite the file.</p>
     *
     * @param scan       what the plugins folder holds right now
     * @param identified hash to Modrinth version, from a bulk lookup of every hash in the scan
     * @return what changed
     */
    public ReconcileReport reconcile(ScanResult scan, Map<String, ModrinthVersion> identified) {

        List<TrackedPlugin> adopted = new ArrayList<>();
        List<InstalledJar> unknown = new ArrayList<>();
        List<InstalledJar> ignored = new ArrayList<>();
        List<InstalledJar> conflicting = new ArrayList<>();
        List<InstalledJar> notAdopted = new ArrayList<>();

        Changes changes = new Changes();
        Set<String> claimed = new HashSet<>();
        List<Unsettled> unsettled = new ArrayList<>();

        // Two rounds, because the strong evidence has to win everywhere before the weak evidence is
        // used anywhere. Settled one plugin at a time, the first to be looked at could claim a jar
        // that a later one owns byte for byte, and that later one would then look deleted.
        for (TrackedPlugin tracked : store.all()) {

            Unsettled left = settle(tracked, scan, identified, claimed, changes);

            if (left != null) {
                unsettled.add(left);
            }
        }

        for (Unsettled left : unsettled) {
            settleByProject(left, scan, identified, claimed, changes);
        }

        for (InstalledJar jar : scan.jars()) {

            if (claimed.contains(jar.fileName())) {
                continue;
            }

            ModrinthVersion version = identified.get(jar.sha512());
            String projectId = version == null ? null : version.projectId();

            if (ignoreList.contains(projectId, jar.sha512())) {
                ignored.add(jar);
                continue;
            }

            if (version == null) {
                unknown.add(jar);
                continue;
            }

            if (store.byProjectId(projectId) != null) {
                conflicting.add(jar);
                continue;
            }

            if (!adoptNewPlugins) {
                notAdopted.add(jar);
                continue;
            }

            adopted.add(adopt(version, jar));
        }

        return ReconcileReport.builder()
                .adopted(adopted)
                .moved(changes.moved)
                .applied(changes.applied)
                .notApplied(changes.notApplied)
                .renamed(changes.renamed)
                .removed(changes.removed)
                .orphaned(changes.orphaned)
                .unknown(unknown)
                .ignored(ignored)
                .conflicting(conflicting)
                .notAdopted(notAdopted)
                .build();
    }

    /**
     * A tracked plugin neither the hash nor the file name could account for.
     *
     * @param nameTaken whether a jar was sitting under its file name, holding something else — the
     *                  difference between a plugin that was replaced and one that simply left
     */
    private record Unsettled(TrackedPlugin plugin, boolean nameTaken) {
    }

    /** What became of the plugins already tracked, gathered as each one is settled. */
    private static final class Changes {

        private final List<TrackedPlugin> moved = new ArrayList<>();
        private final List<TrackedPlugin> applied = new ArrayList<>();
        private final List<TrackedPlugin> notApplied = new ArrayList<>();
        private final List<TrackedPlugin> renamed = new ArrayList<>();
        private final List<TrackedPlugin> removed = new ArrayList<>();
        private final List<TrackedPlugin> orphaned = new ArrayList<>();

    }

    /**
     * Works out what became of one tracked plugin.
     *
     * <p>Matched by hash first and only then by file name, because the hash is the identity: a jar
     * that moved to a different name is the same plugin, while a jar keeping its name with different
     * contents is not.</p>
     */
    private Unsettled settle(TrackedPlugin tracked, ScanResult scan,
                             Map<String, ModrinthVersion> identified, Set<String> claimed,
                             Changes changes) {

        InstalledJar sameContent = tracked.sha512() == null ? null : scan.byHash(tracked.sha512());

        if (sameContent != null) {

            if (!sameContent.fileName().equals(tracked.fileName())) {
                tracked.fileName(sameContent.fileName());
                changes.renamed.add(tracked);
            }

            // Byte for byte what was here before a restart that was supposed to replace it. The
            // server did not take the file from the update folder, and the staged build is still
            // waiting, so the flag stays set and somebody is told.
            if (tracked.pendingRestart()) {
                changes.notApplied.add(tracked);
            }

            // A fresh install is a different case entirely: the jar was always the right one, and
            // reaching a startup at all is the whole of what it was waiting for.
            tracked.pendingLoad(false);

            claimed.add(sameContent.fileName());
            return null;
        }

        InstalledJar sameName = tracked.fileName() == null ? null : scan.byFileName(tracked.fileName());

        if (sameName == null) {
            return new Unsettled(tracked, false);
        }

        ModrinthVersion replacement = identified.get(sameName.sha512());

        if (replacement != null && tracked.projectId().equals(replacement.projectId())) {

            // A staged update becoming the jar on disk is the same event as somebody swapping it by
            // hand — the difference is only that Catalog asked for this one. Without checking the
            // flag first, applying an update reads as "replaced by hand" and the plugin stays
            // marked staged forever, because nothing else ever clears it.
            boolean wasStaged = tracked.pendingRestart();

            tracked.moveTo(replacement, sameName.fileName(), sameName.sha512());
            tracked.pendingRestart(false);
            tracked.stagedAs(null);
            tracked.pendingLoad(false);

            claimed.add(sameName.fileName());
            (wasStaged ? changes.applied : changes.moved).add(tracked);
            return null;
        }

        // The file now holds something else entirely. The name is left unclaimed so whatever
        // actually lives there can be adopted, and the plugin goes to the second round in case it
        // is still on disk somewhere under a name nobody has looked at yet.
        return new Unsettled(tracked, true);
    }

    /**
     * Last resort: find the plugin by the only thing a rename and a new version both leave alone.
     *
     * <p>Reached when neither the contents nor the file name led anywhere, which is what happens
     * when both changed at once — the jar was replaced and renamed in the same downtime. The
     * Modrinth project id survives that, and needs no second opinion: matching it <em>is</em> the
     * proof that this is the same plugin, where a file name or a declared name would still have to
     * be checked against something.</p>
     *
     * <p>Only jars nobody else claimed are considered, so this can never take a jar away from a
     * plugin that matched it exactly. If two are left over for the same project, the first is taken
     * and the other falls through to be reported as a duplicate.</p>
     */
    private void settleByProject(Unsettled left, ScanResult scan,
                                 Map<String, ModrinthVersion> identified, Set<String> claimed,
                                 Changes changes) {

        TrackedPlugin tracked = left.plugin();

        for (InstalledJar jar : scan.jars()) {

            if (claimed.contains(jar.fileName())) {
                continue;
            }

            ModrinthVersion version = identified.get(jar.sha512());

            if (version == null || !tracked.projectId().equals(version.projectId())) {
                continue;
            }

            boolean wasStaged = tracked.pendingRestart();

            tracked.moveTo(version, jar.fileName(), jar.sha512());
            tracked.pendingRestart(false);
            tracked.stagedAs(null);
            tracked.pendingLoad(false);

            claimed.add(jar.fileName());
            (wasStaged ? changes.applied : changes.moved).add(tracked);
            return;
        }

        store.remove(tracked.projectId());
        (left.nameTaken() ? changes.orphaned : changes.removed).add(tracked);
    }

    private TrackedPlugin adopt(ModrinthVersion version, InstalledJar jar) {

        TrackedPlugin plugin = TrackedPlugin.of(version, jar.fileName(), jar.sha512(),
                defaults.channel(), AUTO_TRACK);

        plugin.autoUpdate(defaults.autoUpdate());
        plugin.soakMinutes(defaults.soakMinutes());

        store.put(plugin);

        return plugin;
    }

}
