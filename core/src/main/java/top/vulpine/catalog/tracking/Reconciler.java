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

    public Reconciler(TrackingStore store, IgnoreList ignoreList, TrackingDefaults defaults) {
        this.store = store;
        this.ignoreList = ignoreList;
        this.defaults = defaults;
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
        List<TrackedPlugin> moved = new ArrayList<>();
        List<TrackedPlugin> renamed = new ArrayList<>();
        List<TrackedPlugin> removed = new ArrayList<>();
        List<TrackedPlugin> orphaned = new ArrayList<>();
        List<InstalledJar> unknown = new ArrayList<>();
        List<InstalledJar> ignored = new ArrayList<>();
        List<InstalledJar> conflicting = new ArrayList<>();

        Set<String> claimed = new HashSet<>();

        for (TrackedPlugin tracked : store.all()) {
            settle(tracked, scan, identified, claimed, moved, renamed, removed, orphaned);
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

            adopted.add(adopt(version, jar));
        }

        return ReconcileReport.builder()
                .adopted(adopted)
                .moved(moved)
                .renamed(renamed)
                .removed(removed)
                .orphaned(orphaned)
                .unknown(unknown)
                .ignored(ignored)
                .conflicting(conflicting)
                .duplicates(scan.duplicates())
                .build();
    }

    /**
     * Works out what became of one tracked plugin.
     *
     * <p>Matched by hash first and only then by file name, because the hash is the identity: a jar
     * that moved to a different name is the same plugin, while a jar keeping its name with different
     * contents is not.</p>
     */
    private void settle(TrackedPlugin tracked, ScanResult scan, Map<String, ModrinthVersion> identified,
                        Set<String> claimed, List<TrackedPlugin> moved, List<TrackedPlugin> renamed,
                        List<TrackedPlugin> removed, List<TrackedPlugin> orphaned) {

        InstalledJar sameContent = tracked.sha512() == null ? null : scan.byHash(tracked.sha512());

        if (sameContent != null) {

            if (!sameContent.fileName().equals(tracked.fileName())) {
                tracked.fileName(sameContent.fileName());
                renamed.add(tracked);
            }

            claimed.add(sameContent.fileName());
            return;
        }

        InstalledJar sameName = tracked.fileName() == null ? null : scan.byFileName(tracked.fileName());

        if (sameName == null) {
            store.remove(tracked.projectId());
            removed.add(tracked);
            return;
        }

        ModrinthVersion replacement = identified.get(sameName.sha512());

        if (replacement != null && tracked.projectId().equals(replacement.projectId())) {
            tracked.moveTo(replacement, sameName.fileName(), sameName.sha512());
            claimed.add(sameName.fileName());
            moved.add(tracked);
            return;
        }

        // The file now holds something else entirely. Tracking stops, and the name is left
        // unclaimed so the pass below can adopt whatever actually lives there now.
        store.remove(tracked.projectId());
        orphaned.add(tracked);
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
