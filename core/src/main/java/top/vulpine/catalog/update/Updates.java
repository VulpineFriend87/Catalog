package top.vulpine.catalog.update;

import top.vulpine.catalog.CatalogAction;
import top.vulpine.catalog.Errors;
import top.vulpine.catalog.history.History;
import top.vulpine.catalog.history.HistoryEntry;
import top.vulpine.catalog.install.DependencyResolver;
import top.vulpine.catalog.install.Installer;
import top.vulpine.catalog.modrinth.ModrinthClient;
import top.vulpine.catalog.modrinth.model.ModrinthProject;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.platform.Platform;
import top.vulpine.catalog.tracking.TrackingException;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.trash.Removals;
import top.vulpine.catalog.update.model.ServerTarget;
import top.vulpine.catalog.update.model.UpdateCandidate;
import top.vulpine.commons.log.Logger;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
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
    private final Function<ModrinthVersion, DependencyResolver.Resolution> dependencies;
    private final History history;

    /** Rescans the plugins folder and settles the tracking file against it. */
    private final BooleanSupplier reconcile;

    private volatile List<UpdateCandidate> lastCheck = List.of();
    private volatile Instant checkedAt;

    /**
     * Why the last automatic update of a plugin did not happen, by project id.
     */
    private final Map<String, String> failed = new ConcurrentHashMap<>();

    /**
     * Why each held back build was held back, by version id.
     */
    private final Map<String, String> missingByVersion = new ConcurrentHashMap<>();

    /**
     * Version ids whose failed download is already in the history.
     */
    private final Set<String> failuresReported = ConcurrentHashMap.newKeySet();

    public Updates(Platform platform, ModrinthClient modrinth, TrackingStore tracking,
                   Installer installer, IntSupplier defaultSoakMinutes,
                   Function<ModrinthVersion, DependencyResolver.Resolution> dependencies,
                   History history, BooleanSupplier reconcile) {
        this.platform = platform;
        this.modrinth = modrinth;
        this.tracking = tracking;
        this.installer = installer;
        this.defaultSoakMinutes = defaultSoakMinutes;
        this.dependencies = dependencies;
        this.history = history;
        this.reconcile = reconcile;
    }

    /**
     * The required dependencies a build needs that this server does not have.
     *
     * @param version the build being considered
     * @return what is missing, empty when the build can be installed as it is
     */
    public List<DependencyResolver.Requirement> missingFor(ModrinthVersion version) {

        try {
            return dependencies.apply(version).missing();
        } catch (Exception e) {
            // An answer nobody can get is not evidence of a missing dependency.
            Logger.debug(CatalogAction.UPDATE, "Could not resolve dependencies for "
                    + version.versionNumber() + ": " + Errors.rootMessage(e));
            return List.of();
        }
    }

    /**
     * @return why each failed automatic update failed, by project id
     */
    public Map<String, String> failures() {
        return Map.copyOf(failed);
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
            failed.remove(waiting.projectId());

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

            List<DependencyResolver.Requirement> missing = missingFor(candidate.version());

            if (!missing.isEmpty()) {
                hold(candidate, missing);
                continue;
            }

            try {

                installer.stage(candidate, null);
                failed.remove(candidate.plugin().projectId());

                Logger.info(CatalogAction.UPDATE, "Updated " + candidate.plugin().displayName()
                        + " " + candidate.from() + " -> " + candidate.to()
                        + ", applies on the next restart.");

            } catch (Exception e) {

                String reason = Errors.rootMessage(e);
                failed.put(candidate.plugin().projectId(), reason);

                Logger.warn(CatalogAction.UPDATE, "Could not update "
                        + candidate.plugin().displayName() + ": " + reason);

                if (failuresReported.add(candidate.version().id())) {
                    history.add(HistoryEntry.autoUpdateFailed(candidate.plugin(),
                            candidate.version(), reason));
                }
            }
        }
    }

    /**
     * Keeps an update back because the build needs something this server does not have.
     *
     * <p>Installing it would leave a plugin that cannot load. The update stays on offer, so
     * {@code /catalog list} still shows it and pressing Update walks through what it needs.</p>
     */
    private void hold(UpdateCandidate candidate, List<DependencyResolver.Requirement> missing) {

        String versionId = candidate.version().id();
        String reason = missingByVersion.get(versionId);

        if (reason != null) {
            failed.put(candidate.plugin().projectId(), reason);
            return;
        }

        List<String> names = named(missing);
        reason = String.join(", ", names) + " required missing";

        missingByVersion.put(versionId, reason);
        failed.put(candidate.plugin().projectId(), reason);

        history.add(HistoryEntry.heldBack(candidate.plugin(), candidate.version(),
                missing.size(), names));

        Logger.warn(CatalogAction.UPDATE, "Not updating " + candidate.plugin().displayName()
                + " to " + candidate.to() + " automatically: it needs " + missing.size()
                + " plugin" + (missing.size() == 1 ? "" : "s")
                + " that are not installed. Use /catalog update "
                + candidate.plugin().displayName() + " to install them.");
    }

    /**
     * The names of what is missing, for the line that says an update was held back.
     *
     * <p>A missing dependency is not installed, so nothing on disk knows what it is called. This
     * runs inside the update check, which is already talking to Modrinth, and only when something
     * is actually held back.</p>
     */
    private List<String> named(List<DependencyResolver.Requirement> missing) {

        List<String> ids = new ArrayList<>();

        for (DependencyResolver.Requirement requirement : missing) {
            ids.add(requirement.projectId());
        }

        try {

            Map<String, String> titles = new HashMap<>();

            for (ModrinthProject project : modrinth.projects(ids).join()) {
                titles.put(project.id(), project.title());
            }

            List<String> names = new ArrayList<>();

            for (String id : ids) {
                names.add(titles.getOrDefault(id, id));
            }

            return names;

        } catch (Exception e) {
            // The ids still identify them, which is more than nothing on a line nobody can act on.
            return ids;
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

        if (stagedAndWaiting().isEmpty()) {
            return;
        }

        // Where the jar went cannot be guessed from here: applying a build renames it, and so does
        // replacing one by hand. The scan is the only thing that matches on contents and project id
        // rather than on a name, so it decides. It costs a folder hash and one call to Modrinth,
        // and only runs once something has already taken a staged build.
        reconcile.getAsBoolean();

        List<TrackedPlugin> abandoned = stagedAndWaiting();

        if (abandoned.isEmpty()) {
            return;
        }

        // Anything the scan could not account for really is gone: no jar on disk holds it, under
        // any name.
        for (TrackedPlugin plugin : abandoned) {
            history.add(HistoryEntry.lost(plugin));
            plugin.pendingRestart(false);
            plugin.stagedAs(null);
            plugin.stagedVersionId(null);
            plugin.stagedBy(null);
            Logger.warn(CatalogAction.UPDATE, "The build downloaded for " + plugin.displayName()
                    + " is gone from the update folder and was never applied.");
        }

        tracking.save();
    }

    /**
     * The plugins owed a restart whose staged build is no longer in the update folder.
     *
     * @return the plugins something has taken a build from, never null
     */
    private List<TrackedPlugin> stagedAndWaiting() {

        List<TrackedPlugin> taken = new ArrayList<>();

        for (TrackedPlugin plugin : tracking.pendingRestart()) {
            if (!platform.isStaged(Removals.stagedName(plugin))) {
                taken.add(plugin);
            }
        }

        return taken;
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

}
