package top.vulpine.catalog.install;

import top.vulpine.catalog.hash.Hashing;
import top.vulpine.catalog.history.Event;
import top.vulpine.catalog.history.History;
import top.vulpine.catalog.history.HistoryEntry;
import top.vulpine.catalog.modrinth.model.ModrinthProject;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.platform.Platform;
import top.vulpine.catalog.tracking.TrackingException;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;
import top.vulpine.catalog.trash.Removals;
import top.vulpine.catalog.update.model.UpdateCandidate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Putting builds where the server will load them.
 *
 * <p>Every method blocks, so none may be called on the server main thread.</p>
 */
public final class Installer {

    private final Platform platform;
    private final Downloader downloader;
    private final TrackingStore tracking;
    private final Removals removals;
    private final Supplier<TrackingDefaults> defaults;
    private final History history;

    public Installer(Platform platform, Downloader downloader, TrackingStore tracking,
                     Removals removals, Supplier<TrackingDefaults> defaults, History history) {
        this.platform = platform;
        this.downloader = downloader;
        this.tracking = tracking;
        this.removals = removals;
        this.defaults = defaults;
        this.history = history;
    }

    /**
     * Downloads an update and stages it for the next restart.
     *
     * @param candidate the update to stage
     * @param by        who asked, or null when Catalog decided on its own
     */
    public void stage(UpdateCandidate candidate, String by) throws TrackingException {
        stage(candidate.plugin(), candidate.version(), by, Event.UPDATE_STAGED);
    }

    /**
     * Downloads any build of an already-installed plugin and stages it for the next restart.
     *
     * @param plugin  the tracked plugin to replace
     * @param version the build to put in its place
     * @param by      who asked, or null when Catalog decided on its own
     * @param as      how it is recorded: an update, a switch or a rollback
     */
    public void stage(TrackedPlugin plugin, ModrinthVersion version, String by, Event as)
            throws TrackingException {

        HistoryEntry entry = HistoryEntry.staged(plugin, version, by, as);

        Path staged = downloader.fetch(version, Runtime.version().feature());

        // The downloader already writes it under the file name Modrinth publishes it as.
        String published = staged.getFileName().toString();

        platform.applyAtRestart(staged, published);

        plugin.stagedAs(published);
        plugin.stagedVersionId(version.id());
        plugin.stagedBy(by);
        plugin.pendingRestart(true);
        tracking.save();

        history.add(entry);
    }

    /**
     * Drops a staged build so the next restart leaves the plugin as it is.
     *
     * @param plugin the plugin to leave alone
     * @param by     who asked
     * @return false when the staged file could not be deleted, in which case it will still apply
     */
    public boolean cancel(TrackedPlugin plugin, String by) throws TrackingException {

        if (!platform.cancelStaged(Removals.stagedName(plugin))) {
            return false;
        }

        plugin.stagedAs(null);
        plugin.stagedVersionId(null);
        plugin.stagedBy(null);
        plugin.pendingRestart(false);
        tracking.save();

        history.add(HistoryEntry.cancelled(plugin, by));
        return true;
    }

    /**
     * Downloads a plugin that is not installed and puts it in the plugins folder.
     *
     * @param project the project being installed
     * @param version the build to install
     * @param channel the channel this plugin should follow from now on
     * @param by      who asked, for the audit trail
     * @return the new tracking record
     */
    public TrackedPlugin install(ModrinthProject project, ModrinthVersion version,
                                 ReleaseChannel channel, String by) throws TrackingException {
        return install(List.of(new Pending(project, version, channel, true, null)), by).get(0);
    }

    /**
     * Installs several builds together, or none of them.
     *
     * @param pending what to install, in the order it should be recorded
     * @param by      who asked
     * @return the tracking records, in the same order
     * @throws InstallException if any download or check fails, having written nothing
     */
    public List<TrackedPlugin> install(List<Pending> pending, String by) throws TrackingException {

        Map<Pending, Path> staged = new LinkedHashMap<>();

        for (Pending one : pending) {
            staged.put(one, downloader.fetch(one.version(), Runtime.version().feature()));
        }

        TrackingDefaults configured = defaults.get();
        List<TrackedPlugin> installed = new ArrayList<>();

        for (Map.Entry<Pending, Path> entry : staged.entrySet()) {

            Pending one = entry.getKey();
            String hash = one.version().primaryFile().sha512();
            String fileName = entry.getValue().getFileName().toString();

            place(entry.getValue(), platform.pluginsFolder().resolve(fileName), hash);

            TrackedPlugin tracked = TrackedPlugin.of(one.version(), fileName, hash,
                    one.channel(), by);

            tracked.name(one.project().title());
            tracked.slug(one.project().slug());
            tracked.autoUpdate(configured.autoUpdate());
            tracked.explicit(one.explicit());
            tracked.pendingLoad(!removals.stillRunning(hash));

            tracking.put(tracked);
            installed.add(tracked);
        }

        tracking.save();

        for (Map.Entry<Pending, Path> entry : staged.entrySet()) {

            TrackedPlugin tracked = tracking.byProjectId(entry.getKey().project().id());

            if (tracked != null) {
                history.add(HistoryEntry.installed(tracked, by, entry.getKey().requiredBy()));
            }
        }

        return installed;
    }

    /**
     * A build about to be installed.
     *
     * @param explicit   false when it is only here because something else named it, which is what
     *                   lets a later autoremove offer it once nothing needs it
     * @param requiredBy the plugin whose install pulled this one in, or null when it was asked for
     *                   outright. The root rather than the immediate parent: six months later the
     *                   useful answer is which install brought it, not which link in the chain.
     */
    public record Pending(ModrinthProject project, ModrinthVersion version, ReleaseChannel channel,
                          boolean explicit, String requiredBy) {
    }

    /**
     * Puts a downloaded build into the plugins folder.
     *
     * <p>The hash is what makes that safe. A <em>different</em> build sharing the file name is a
     * genuine collision and still refused, because replacing a jar the server has open is what
     * staging exists for.</p>
     */
    private void place(Path staged, Path target, String sha512) {

        if (Files.exists(target)) {

            boolean sameBuild = sha512 != null && sha512.equalsIgnoreCase(hashOf(target));

            if (!sameBuild || !removals.claimPendingDelete(target)) {
                throw new InstallException(target.getFileName()
                        + " already exists in the plugins folder.");
            }

            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
                // Staging is emptied on startup, so a download left behind costs one file until then.
            }

            return;
        }

        try {
            Files.move(staged, target);
        } catch (IOException e) {
            throw new InstallException("Could not write " + target.getFileName() + ": "
                    + e.getMessage(), e);
        }
    }

    static String hashOf(Path jar) {

        try {
            return Files.isRegularFile(jar) ? Hashing.sha512(jar) : null;
        } catch (IOException e) {
            return null;
        }
    }

}
