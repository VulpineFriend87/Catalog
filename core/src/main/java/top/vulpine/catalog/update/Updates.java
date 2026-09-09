package top.vulpine.catalog.update;

import top.vulpine.catalog.CatalogAction;
import top.vulpine.catalog.Errors;
import top.vulpine.catalog.hash.Hashing;
import top.vulpine.catalog.install.Installer;
import top.vulpine.catalog.modrinth.ModrinthClient;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.platform.Platform;
import top.vulpine.catalog.tracking.TrackingException;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.trash.Removals;
import top.vulpine.catalog.update.model.ServerTarget;
import top.vulpine.catalog.update.model.UpdateCandidate;
import top.vulpine.commons.log.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * What is out of date, and what to install automatically.
 *
 * <p>Every method blocks, so none may be called on the server main thread.</p>
 */
public final class Updates {

    private final Platform platform;
    private final ModrinthClient modrinth;
    private final TrackingStore tracking;
    private final Installer installer;
    private final IntSupplier defaultSoakMinutes;

    private volatile List<UpdateCandidate> lastCheck = List.of();
    private volatile Instant checkedAt;

    public Updates(Platform platform, ModrinthClient modrinth, TrackingStore tracking,
                   Installer installer, IntSupplier defaultSoakMinutes) {
        this.platform = platform;
        this.modrinth = modrinth;
        this.tracking = tracking;
        this.installer = installer;
        this.defaultSoakMinutes = defaultSoakMinutes;
    }

    /**
     * @return when the last check ran, or null if none has
     */
    public Instant checkedAt() {
        return checkedAt;
    }

    /**
     * Asks Modrinth what is out of date and remembers the answer.
     *
     * @return the available updates
     */
    public List<UpdateCandidate> refresh() throws TrackingException {

        noticeStagedApplied();

        ServerTarget target = platform.target();
        Logger.debug(CatalogAction.UPDATE, "Checking against " + target + ", asking for loaders "
                + String.join(", ", target.loaders())
                + " and game versions " + String.join(", ", target.gameVersions()) + ".");

        for (TrackedPlugin tracked : tracking.all()) {
            Logger.debug(CatalogAction.UPDATE, "  asking about " + state(tracked));
        }

        lastCheck = new UpdateChecker(modrinth, tracking).check(target);
        checkedAt = Instant.now();

        Set<String> offered = new HashSet<>();

        for (UpdateCandidate candidate : lastCheck) {

            offered.add(candidate.plugin().projectId());

            Logger.debug(CatalogAction.UPDATE, "  Modrinth offers " + candidate.plugin().displayName()
                    + " " + candidate.from() + " -> " + candidate.to()
                    + (candidate.plugin().awaitingRestart()
                            ? "; hidden from the list and skipped by auto-update, because it is"
                                    + " already waiting for a restart"
                            : ""));
        }

        for (TrackedPlugin tracked : tracking.all()) {

            if (!offered.contains(tracked.projectId())) {
                Logger.debug(CatalogAction.UPDATE, "  no newer build offered for "
                        + tracked.displayName() + (tracked.isPinned() ? ", which is held" : ""));
            }
        }

        return lastCheck;
    }

    /**
     * Checks, then installs whatever the policy allows.
     */
    public void check() {

        if (tracking.size() == 0) {
            return;
        }

        List<UpdateCandidate> candidates;

        try {
            candidates = refresh();
        } catch (Exception e) {
            Logger.warn(CatalogAction.UPDATE, "Could not check for updates: " + Errors.rootMessage(e));
            return;
        }

        Logger.debug(CatalogAction.UPDATE, candidates.size() + " update"
                + (candidates.size() == 1 ? "" : "s") + " available.");

        applyAutomatic(candidates);
    }

    /**
     * Runs auto updates.
     */
    private void applyAutomatic(List<UpdateCandidate> candidates) {

        AutoUpdatePolicy policy = new AutoUpdatePolicy(defaultSoakMinutes.getAsInt());
        Instant now = Instant.now();
        List<UpdateCandidate> ready = policy.readyToApply(candidates, now);

        for (UpdateCandidate candidate : candidates) {

            if (ready.contains(candidate)) {
                continue;
            }

            TrackedPlugin waiting = candidate.plugin();

            Logger.debug(CatalogAction.UPDATE, "Not updating " + waiting.displayName()
                    + " on its own: " + (!waiting.autoUpdate() ? "auto-update is off"
                            : waiting.isPinned() ? "it is held"
                            : waiting.awaitingRestart() ? "it is already waiting for a restart"
                            : policy.soaking(candidate, now)
                                    ? "the build is still soaking, " + policy.soakMinutes(waiting)
                                            + " minutes from " + candidate.version().datePublished()
                            : "the policy declined it"));
        }

        for (UpdateCandidate candidate : ready) {

            try {

                installer.stage(candidate);

                Logger.info(CatalogAction.UPDATE, "Updated " + candidate.plugin().displayName()
                        + " " + candidate.from() + " -> " + candidate.to()
                        + ", applies on the next restart.");

            } catch (Exception e) {
                Logger.warn(CatalogAction.UPDATE, "Could not update "
                        + candidate.plugin().displayName() + ": " + Errors.rootMessage(e));
            }
        }
    }

    /**
     * The updates still worth offering: what the last check found, minus anything already staged.
     *
     * @return the open update candidates
     */
    public List<UpdateCandidate> open() {

        List<UpdateCandidate> out = new ArrayList<>();

        for (UpdateCandidate candidate : lastCheck) {
            if (!candidate.plugin().pendingRestart()) {
                out.add(candidate);
            }
        }

        return out;
    }

    /**
     * The open updates keyed by project id, which is how the list annotates its rows.
     *
     * @return project id to candidate
     */
    public Map<String, UpdateCandidate> byProject() {

        Map<String, UpdateCandidate> byProject = new HashMap<>();

        for (UpdateCandidate candidate : open()) {
            byProject.put(candidate.plugin().projectId(), candidate);
        }

        return byProject;
    }

    /**
     * Notices a staged build that something else already applied.
     *
     * <p>Paper consumes the update folder in {@code FileProviderSource#checkUpdate}, which runs for
     * every plugin file it loads, not only during the startup scan. So a reload tool loading a
     * single plugin applies whatever Catalog staged for it, there and then. What Catalog must not
     * do is keep insisting a restart is owed for a build that is already running.</p>
     */
    private void noticeStagedApplied() throws TrackingException {

        Map<String, TrackedPlugin> rehashed = new HashMap<>();
        List<TrackedPlugin> abandoned = new ArrayList<>();

        for (TrackedPlugin plugin : tracking.pendingRestart()) {

            // Still sitting there waiting for a restart, which is the normal case.
            if (platform.isStaged(Removals.stagedName(plugin))) {
                continue;
            }

            String hash = hashOf(platform.pluginsFolder().resolve(plugin.fileName()));

            if (hash == null || hash.equals(plugin.sha512())) {
                // Gone from the update folder without the jar changing: somebody deleted it.
                abandoned.add(plugin);
            } else {
                rehashed.put(hash, plugin);
            }
        }

        if (rehashed.isEmpty() && abandoned.isEmpty()) {
            return;
        }

        for (TrackedPlugin plugin : abandoned) {
            plugin.pendingRestart(false);
            plugin.stagedAs(null);
            Logger.warn(CatalogAction.UPDATE, "The build staged for " + plugin.displayName()
                    + " is gone from the update folder and was never applied.");
        }

        identifyApplied(rehashed);
        tracking.save();
    }

    /**
     * Re-identifies jars that changed underneath Catalog and records what they became.
     */
    private void identifyApplied(Map<String, TrackedPlugin> byHash) {

        if (byHash.isEmpty()) {
            return;
        }

        Map<String, ModrinthVersion> identified;

        try {
            identified = modrinth.identify(byHash.keySet()).join();
        } catch (Exception e) {
            // The flags stay set and the next startup sorts it out from the hashes on disk.
            Logger.debug(CatalogAction.UPDATE, "Could not identify applied builds: "
                    + Errors.rootMessage(e));
            return;
        }

        for (Map.Entry<String, TrackedPlugin> entry : byHash.entrySet()) {

            TrackedPlugin plugin = entry.getValue();
            ModrinthVersion became = identified.get(entry.getKey());

            if (became == null || !plugin.projectId().equals(became.projectId())) {
                continue;
            }

            plugin.moveTo(became, plugin.fileName(), entry.getKey());
            plugin.pendingRestart(false);
            plugin.stagedAs(null);

            Logger.info(CatalogAction.UPDATE, plugin.displayName() + " is now "
                    + became.versionNumber() + ", applied without a restart by something else.");
        }
    }

    /**
     * Everything about a tracked plugin that decides whether it can be updated, in one line.
     */
    private static String state(TrackedPlugin tracked) {

        String hash = tracked.sha512();

        return tracked.displayName()
                + " project=" + tracked.projectId()
                + " version=" + tracked.versionNumber() + " (" + tracked.versionId() + ")"
                + " published=" + tracked.datePublished()
                + " channel=" + tracked.channel().apiName()
                + " sha512=" + (hash == null ? "none" : hash.substring(0, Math.min(12, hash.length())))
                + " auto=" + tracked.autoUpdate()
                + " soak=" + tracked.soakMinutes()
                + " held=" + tracked.isPinned()
                + " pendingRestart=" + tracked.pendingRestart()
                + " pendingLoad=" + tracked.pendingLoad();
    }

    private static String hashOf(Path jar) {

        try {
            return Files.isRegularFile(jar) ? Hashing.sha512(jar) : null;
        } catch (IOException e) {
            return null;
        }
    }

}
