package top.vulpine.catalog.trash;

import top.vulpine.catalog.install.InstallException;
import top.vulpine.catalog.platform.Platform;
import top.vulpine.catalog.tracking.TrackingException;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;
import top.vulpine.catalog.trash.model.TrashEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Removing plugins, and putting them back.
 */
public final class Removals {

    private final Platform platform;
    private final TrashBin trash;
    private final TrackingStore tracking;
    private final Supplier<TrackingDefaults> defaults;
    private final Instant startedAt;

    /**
     * Jars this server would not let us delete, to be removed once it has let go of them.
     *
     * <p>A set drained at shutdown rather than {@link java.io.File#deleteOnExit()}, because a
     * removal can be undone and {@code deleteOnExit} cannot be called off: an undone removal would
     * still lose the file at the next shutdown.</p>
     */
    private final Set<Path> deleteAtShutdown = ConcurrentHashMap.newKeySet();

    public Removals(Platform platform, TrashBin trash, TrackingStore tracking,
                    Supplier<TrackingDefaults> defaults, Instant startedAt) {
        this.platform = platform;
        this.trash = trash;
        this.tracking = tracking;
        this.defaults = defaults;
        this.startedAt = startedAt;
    }

    /**
     * Moves a plugin's jar to the trash and stops tracking it.
     *
     * @param plugin the plugin to remove
     * @param by     who asked
     * @return what was binned, which identifies this exact removal so it can be undone, or null
     *         if there was no file to bin
     */
    public TrashBin.Result uninstall(TrackedPlugin plugin, String by) throws TrackingException {

        Path jar = platform.pluginsFolder().resolve(plugin.fileName());
        TrashBin.Result result = null;

        if (Files.isRegularFile(jar)) {

            result = trash.bin(jar, plugin, by);

            if (!result.deleted()) {
                deleteAtShutdown.add(jar);
            }
        }

        tracking.remove(plugin.projectId());
        tracking.save();

        return result;
    }

    /**
     * Drops a build staged for a plugin so the next restart does not put it back.
     *
     * @param plugin the plugin being removed
     * @return true if nothing is left staged
     */
    public boolean cancelStagedFor(TrackedPlugin plugin) {
        return platform.cancelStaged(stagedName(plugin));
    }

    /**
     * Everything currently in the trash, newest removal first.
     */
    public List<TrashEntry> list() {
        return trash.list();
    }

    /**
     * One removal by the name it is filed under.
     *
     * @param storedAs the id carried by an undo button
     * @return the entry, or null if it has already been restored or pruned
     */
    public TrashEntry find(String storedAs) {
        return trash.find(storedAs);
    }

    /**
     * Puts a removed plugin back and starts tracking it again.
     *
     * @param entry what to restore
     * @param by    who asked
     * @return the plugin Catalog is tracking again, or null if it was never tracked to begin with
     * @throws InstallException if the jar is gone, or something already occupies its file name
     */
    public TrackedPlugin restore(TrashEntry entry, String by) throws TrackingException {

        if (entry.projectId() != null && tracking.byProjectId(entry.projectId()) != null) {
            throw new InstallException(entry.displayName() + " is already installed.");
        }

        Path target = platform.pluginsFolder().resolve(entry.fileName());

        // A removal this server would not carry out left the jar exactly where it belongs, so
        // undoing that one is a matter of calling the deletion off rather than copying anything.
        if (deleteAtShutdown.remove(target)) {
            trash.discard(entry);
        } else {
            trash.restore(entry, target);
        }

        return track(entry, by);
    }

    /**
     * Rebuilds the tracking record a restored plugin had, from what was written down when it was
     * removed rather than from Modrinth: the version it was on may no longer be the newest, and by
     * now may not even be listed.
     *
     * <p>Whether a restart is owed follows from when the removal happened, not from asking the
     * server what is loaded: nothing is ever unloaded without one.</p>
     */
    private TrackedPlugin track(TrashEntry entry, String by) throws TrackingException {

        if (entry.projectId() == null) {
            return null;
        }

        TrackingDefaults configured = defaults.get();
        TrackedPlugin tracked = new TrackedPlugin();

        tracked.projectId(entry.projectId());
        tracked.slug(entry.slug());
        tracked.name(entry.name());
        tracked.versionId(entry.versionId());
        tracked.versionNumber(entry.versionNumber());
        tracked.fileName(entry.fileName());
        tracked.sha512(entry.sha512());
        tracked.channel(entry.channel() == null ? configured.channel() : entry.channel());
        tracked.autoUpdate(configured.autoUpdate());
        tracked.installedBy(by);
        tracked.installedAt(Instant.now());
        tracked.pendingLoad(!removedWhileRunning(entry.removedAt()));

        tracking.put(tracked);
        tracking.save();

        return tracked;
    }

    /**
     * Whether a build that has just been written to the plugins folder is already loaded.
     *
     * @param sha512 the hash of the build being installed
     * @return true if a removal from this session took the same build away
     */
    public boolean stillRunning(String sha512) {

        if (sha512 == null) {
            return false;
        }

        for (TrashEntry entry : trash.list()) {

            if (sha512.equalsIgnoreCase(entry.sha512()) && removedWhileRunning(entry.removedAt())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Whether a removal happened while this server has been up, which is what decides if the plugin
     * it took away is still loaded. Nothing is ever unloaded without a restart, so it is.
     */
    private boolean removedWhileRunning(Instant removedAt) {
        return removedAt != null && !removedAt.isBefore(startedAt);
    }

    /**
     * Claims a jar left in place because this server had it locked.
     *
     * @param jar the file being written over
     * @return true if the pending deletion was called off, so the file may be reused
     */
    public boolean claimPendingDelete(Path jar) {
        return deleteAtShutdown.remove(jar);
    }

    /**
     * Deletes one removal permanently.
     *
     * @param entry what to delete
     */
    public void discard(TrashEntry entry) {
        trash.discard(entry);
    }

    /**
     * Deletes every removal permanently.
     *
     * @return how many went
     */
    public int empty() {
        return trash.empty();
    }

    /**
     * Drops removals older than the given window.
     *
     * @param days how long a removal is kept, or zero to keep them forever
     * @return how many were dropped
     */
    public int prune(int days) {
        return days <= 0 ? 0 : trash.prune(Duration.ofDays(days), Instant.now());
    }

    /**
     * Deletes the jars this server would not let go of, once it has.
     */
    public void finish() {

        for (Path jar : deleteAtShutdown) {

            try {
                Files.deleteIfExists(jar);
            } catch (IOException ignored) {
                // There is no longer anywhere to report this to.
            }
        }
    }

    /**
     * Where a staged build is sitting.
     *
     * <p>Falls back to the installed name for records written before builds were staged under the
     * name their author published, so an update queued by an older version is still found.</p>
     *
     * @param plugin the plugin to look up
     * @return the file name a staged build would have
     */
    public static String stagedName(TrackedPlugin plugin) {
        return plugin.stagedAs() != null ? plugin.stagedAs() : plugin.fileName();
    }

}
