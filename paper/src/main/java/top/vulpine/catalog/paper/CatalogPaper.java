package top.vulpine.catalog.paper;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.yaml.bukkit.YamlBukkitConfigurer;
import eu.okaeri.configs.yaml.bukkit.serdes.SerdesBukkit;
import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.BukkitLamp;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import top.vulpine.catalog.hash.Hashing;
import top.vulpine.catalog.install.DependencyResolver;
import top.vulpine.catalog.install.Downloader;
import top.vulpine.catalog.install.InstallException;
import top.vulpine.catalog.jar.JarScanner;
import top.vulpine.catalog.jar.model.InstalledJar;
import top.vulpine.catalog.jar.model.ScanResult;
import top.vulpine.catalog.modrinth.ModrinthClient;
import top.vulpine.catalog.modrinth.model.ModrinthProject;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.modrinth.model.SearchResults;
import top.vulpine.catalog.paper.command.ClickContext;
import top.vulpine.catalog.paper.command.ClickCommand;
import top.vulpine.catalog.paper.command.MainCommand;
import top.vulpine.catalog.paper.command.annotation.RequiresPermission;
import top.vulpine.catalog.paper.config.Config;
import top.vulpine.catalog.paper.util.PermissionChecker;
import top.vulpine.catalog.tracking.IgnoreList;
import top.vulpine.catalog.tracking.Reconciler;
import top.vulpine.catalog.tracking.Settings;
import top.vulpine.catalog.tracking.TrackingException;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.ReconcileReport;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;
import top.vulpine.catalog.trash.TrashBin;
import top.vulpine.catalog.trash.model.TrashEntry;
import top.vulpine.catalog.update.AutoUpdatePolicy;
import top.vulpine.catalog.update.UpdateChecker;
import top.vulpine.catalog.update.model.ServerPlatform;
import top.vulpine.catalog.update.model.ServerTarget;
import top.vulpine.catalog.update.model.UpdateCandidate;
import top.vulpine.commons.log.LogAction;
import top.vulpine.commons.log.LogLevel;
import top.vulpine.commons.log.Logger;
import top.vulpine.commons.text.Colorize;
import top.vulpine.commons.text.Dialect;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Catalog for Paper, Purpur, and Folia.
 */
@Getter
public final class CatalogPaper extends JavaPlugin {

    private static final String MODRINTH = "https://modrinth.com/plugin/catalog";

    private static final int PLUGIN_ID = 33849;

    private Config configuration;
    private FoliaLib foliaLib;
    private ModrinthClient modrinth;
    private TrackingStore tracking;
    private IgnoreList ignored;
    private Downloader downloader;
    private TrashBin trash;
    private Settings settings;

    /**
     * Jars this server would not let us delete, to be removed once it has let go of them.
     *
     * <p>A set drained by a shutdown hook rather than {@link java.io.File#deleteOnExit()}, because
     * a removal can be undone and {@code deleteOnExit} cannot be called off: an undone removal
     * would still lose the file at the next shutdown.</p>
     */
    private final Set<Path> deleteAtShutdown = ConcurrentHashMap.newKeySet();

    /**
     * When this server came up.
     */
    private final Instant startedAt = Instant.now();

    private volatile List<UpdateCandidate> lastCheck = List.of();
    private volatile Instant checkedAt;
    private volatile int unmanaged;

    private enum Action implements LogAction {
        CONFIG, SETUP, SCAN, TRACK, UPDATE
    }

    @Override
    public void onEnable() {

        if (!hasPaperApi()) {
            getLogger().severe("Catalog needs Paper or a fork of it, such as Purpur or Folia.");
            getLogger().severe("Latest version: " + MODRINTH);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Colorize.init(Dialect.MODERN);
        Logger.builder().logger(getComponentLogger()).build();

        if (!loadConfiguration()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.foliaLib = new FoliaLib(this);
        Logger.debug(Action.SETUP, "Scheduling through FoliaLib, detected platform: "
                + foliaLib.getImplType() + ".");

        String[] banner = {
                "",
                "<light_purple>    ┏┓     ┓",
                "<light_purple>    ┃ ┏┓╋┏┓┃┏┓┏┓",
                "<light_purple>    ┗┛┗┻┗┗┻┗┗┛┗┫",
                "<light_purple>               ┛",
                "",
                "<white>    By <light_purple>" + String.join(", ", getDescription().getAuthors()),
                "<white>    Version: <light_purple>" + getDescription().getVersion(),
                ""
        };

        for (String line : banner) {
            Logger.system(line);
        }

        this.modrinth = ModrinthClient.builder()
                .userAgent("VulpineFriend87/Catalog/" + getDescription().getVersion() + " (vulpine.top)")
                .token(configuration.modrinth.token)
                .cacheDirectory(getDataFolder().toPath().resolve("cache"))
                .build();

        if (!loadState()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Path data = getDataFolder().toPath();
        this.downloader = new Downloader(modrinth, data.resolve("staging"));
        this.trash = new TrashBin(data.resolve("trash"));
        this.settings = new Settings(tracking, this::defaults);

        Lamp<BukkitCommandActor> lamp = BukkitLamp.builder(this)
                .permissionForAnnotation(RequiresPermission.class, annotation ->
                        actor -> PermissionChecker.hasPermission(actor.sender(), annotation.value()))
                .build();

        ClickContext clicks = new ClickContext();

        lamp.register(new MainCommand(this, clicks), new ClickCommand(clicks));
        clicks.dispatcher(lamp);

        Logger.debug(Action.SETUP, "Initializing metrics...");
        new Metrics(this, PLUGIN_ID);

        Runtime.getRuntime().addShutdownHook(new Thread(this::finishRemovals, "Catalog removals"));

        getScheduler().runAsync(task -> index());
        getScheduler().runAsync(task -> pruneTrash());
        scheduleUpdateChecks();
    }

    @Override
    public void onDisable() {

        if (modrinth != null) {
            modrinth.close();
        }

        if (foliaLib != null) {
            foliaLib.getScheduler().cancelAllTasks();
        }

        Logger.close();
    }

    private void applyLogging() {

        Logger.builder()
                .logger(getComponentLogger())
                .level(configuration.logLevel)
                .trace(configuration.logLevel == LogLevel.DEBUG ? getDataFolder() : null)
                .build();
    }

    public boolean loadConfiguration() {

        try {
            configuration = ConfigManager.create(Config.class, it -> {
                it.withConfigurer(new YamlBukkitConfigurer(), new SerdesBukkit());
                it.withBindFile(new File(getDataFolder(), "config.yml"));
                it.saveDefaults();
                it.load(true);
            });
        } catch (Exception e) {
            Logger.error(Action.CONFIG, "Failed to load configuration: " + e.getMessage());
            e.printStackTrace();
            return false;
        }

        applyLogging();
        return true;
    }

    private boolean loadState() {

        Path data = getDataFolder().toPath().resolve("data");

        tracking = new TrackingStore(data.resolve("tracked.json"));
        ignored = new IgnoreList(data.resolve("ignored.json"));

        try {
            tracking.load();
            ignored.load();
        } catch (TrackingException e) {
            Logger.error(Action.CONFIG, e.getMessage());
            return false;
        }

        Logger.debug(Action.SETUP, "Loaded " + tracking.size() + " tracked plugins, "
                + ignored.size() + " ignore entries.");

        return true;
    }

    private void index() {

        downloader.clean();

        long started = System.currentTimeMillis();
        ScanResult scan = new JarScanner(pluginsFolder()).scan();

        List<String> hashes = new ArrayList<>();

        for (InstalledJar jar : scan.jars()) {
            if (jar.sha512() != null) {
                hashes.add(jar.sha512());
            }
        }

        Logger.debug(Action.SCAN, "Hashed " + hashes.size() + " jars in "
                + (System.currentTimeMillis() - started) + "ms.");

        for (InstalledJar jar : scan.unreadable()) {
            Logger.warn(Action.SCAN, "Could not read " + jar.fileName()
                    + ", so it is not indexed. On Windows this usually means the file is locked.");
        }

        Map<String, ModrinthVersion> identified;

        try {
            identified = modrinth.identify(hashes).join();
        } catch (Exception e) {
            // Without an answer every tracked plugin would look unidentifiable, and reconciling on
            // that would untrack the entire server over a network blip.
            Logger.warn(Action.SCAN, "Could not reach Modrinth, so nothing was reconciled: "
                    + rootMessage(e));
            return;
        }

        Reconciler reconciler = new Reconciler(tracking, ignored, defaults(),
                configuration.tracking.autoTrack);

        ReconcileReport report = reconciler.reconcile(scan, identified);
        boolean named = nameTrackedPlugins();

        if (report.hasChanges() || named) {
            try {
                tracking.save();
            } catch (TrackingException e) {
                Logger.error(Action.TRACK, e.getMessage());
            }
        }

        unmanaged = report.unknown().size();

        describe(report, scan);
        checkForUpdates();
    }

    private void checkForUpdates() {

        if (tracking.size() == 0) {
            return;
        }

        List<UpdateCandidate> candidates;

        try {
            candidates = refreshUpdates();
        } catch (Exception e) {
            Logger.warn(Action.UPDATE, "Could not check for updates: " + rootMessage(e));
            return;
        }

        Logger.debug(Action.UPDATE, candidates.size() + " update"
                + (candidates.size() == 1 ? "" : "s") + " available.");

        applyAutomatic(candidates);
    }

    /**
     * Runs auto updates.
     */
    private void applyAutomatic(List<UpdateCandidate> candidates) {

        AutoUpdatePolicy policy = new AutoUpdatePolicy(configuration.tracking.defaults.soakMinutes);
        Instant now = Instant.now();
        List<UpdateCandidate> ready = policy.readyToApply(candidates, now);

        for (UpdateCandidate candidate : candidates) {

            if (ready.contains(candidate)) {
                continue;
            }

            TrackedPlugin waiting = candidate.plugin();

            Logger.debug(Action.UPDATE, "Not updating " + waiting.displayName()
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

                stage(candidate);

                Logger.info(Action.UPDATE, "Updated " + candidate.plugin().displayName()
                        + " " + candidate.from() + " -> " + candidate.to()
                        + ", applies on the next restart.");

            } catch (Exception e) {
                Logger.warn(Action.UPDATE, "Could not update " + candidate.plugin().displayName()
                        + ": " + rootMessage(e));
            }
        }
    }

    /**
     * Asks again on a timer.
     */
    private void scheduleUpdateChecks() {

        int minutes = configuration.updates.checkIntervalMinutes;

        if (minutes <= 0) {
            Logger.debug(Action.SETUP, "Periodic update checks are off.");
            return;
        }

        getScheduler().runTimerAsync(this::checkForUpdates, minutes, minutes, TimeUnit.MINUTES);
        Logger.debug(Action.SETUP, "Checking for updates every " + minutes + " minutes.");
    }

    /**
     * Asks Modrinth what is out of date and remembers the answer.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @return the available updates
     */
    public List<UpdateCandidate> refreshUpdates() {

        noticeStagedApplied();

        ServerTarget target = target();
        Logger.debug(Action.UPDATE, "Checking against " + target + ", asking for loaders "
                + String.join(", ", target.loaders())
                + " and game versions " + String.join(", ", target.gameVersions()) + ".");

        for (TrackedPlugin tracked : tracking.all()) {
            Logger.debug(Action.UPDATE, "  asking about " + state(tracked));
        }

        lastCheck = new UpdateChecker(modrinth, tracking).check(target);
        checkedAt = Instant.now();

        Set<String> offered = new HashSet<>();

        for (UpdateCandidate candidate : lastCheck) {

            offered.add(candidate.plugin().projectId());

            Logger.debug(Action.UPDATE, "  Modrinth offers " + candidate.plugin().displayName()
                    + " " + candidate.from() + " -> " + candidate.to()
                    + (candidate.plugin().awaitingRestart()
                            ? "; hidden from the list and skipped by auto-update, because it is"
                                    + " already waiting for a restart"
                            : ""));
        }

        for (TrackedPlugin tracked : tracking.all()) {

            if (!offered.contains(tracked.projectId())) {
                Logger.debug(Action.UPDATE, "  no newer build offered for " + tracked.displayName()
                        + (tracked.isPinned() ? ", which is held" : ""));
            }
        }

        return lastCheck;
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

    /**
     * Notices a staged build that something else already applied.
     *
     * <p>Paper consumes the update folder in {@code FileProviderSource#checkUpdate}, which runs for
     * every plugin file it loads — not only during the startup scan. So a reload tool loading a
     * single plugin applies whatever Catalog staged for it, there and then. That is a fine outcome,
     * and Catalog has no say in it; what it must not do is keep insisting a restart is owed for a
     * build that is already running.</p>
     */
    private void noticeStagedApplied() {

        Path updates = getServer().getUpdateFolderFile().toPath();
        Map<String, TrackedPlugin> rehashed = new HashMap<>();
        List<TrackedPlugin> abandoned = new ArrayList<>();

        for (TrackedPlugin plugin : tracking.pendingRestart()) {

            // Still sitting there waiting for a restart, which is the normal case.
            if (Files.exists(updates.resolve(stagedName(plugin)))) {
                continue;
            }

            String hash = hashOf(pluginsFolder().resolve(plugin.fileName()));

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
            Logger.warn(Action.UPDATE, "The build staged for " + plugin.displayName()
                    + " is gone from the update folder and was never applied.");
        }

        identifyApplied(rehashed);
        saveTracking();
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
            Logger.debug(Action.UPDATE, "Could not identify applied builds: " + rootMessage(e));
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

            Logger.info(Action.UPDATE, plugin.displayName() + " is now "
                    + became.versionNumber() + ", applied without a restart by something else.");
        }
    }

    /**
     * Where a staged build is sitting in the update folder.
     *
     * <p>Falls back to the installed name for records written before builds were staged under the
     * name their author published, so an update queued by an older version is still found.</p>
     */
    private static String stagedName(TrackedPlugin plugin) {
        return plugin.stagedAs() != null ? plugin.stagedAs() : plugin.fileName();
    }

    private static String hashOf(Path jar) {

        try {
            return Files.isRegularFile(jar) ? Hashing.sha512(jar) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The updates still worth offering: what the last check found, minus anything already staged.
     *
     * @return the open update candidates
     */
    public List<UpdateCandidate> updates() {

        List<UpdateCandidate> open = new ArrayList<>();

        for (UpdateCandidate candidate : lastCheck) {
            if (!candidate.plugin().pendingRestart()) {
                open.add(candidate);
            }
        }

        return open;
    }

    /**
     * The open updates keyed by project id, which is how the list annotates its rows.
     *
     * @return project id to candidate
     */
    public Map<String, UpdateCandidate> updatesByProject() {

        Map<String, UpdateCandidate> byProject = new HashMap<>();

        for (UpdateCandidate candidate : updates()) {
            byProject.put(candidate.plugin().projectId(), candidate);
        }

        return byProject;
    }

    /**
     * The build an install would fetch for a project.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param idOrSlug the project to look at
     * @return the build to offer, or null when nothing published runs here
     */
    public ModrinthVersion installTarget(String idOrSlug) {
        return installTarget(compatibleVersions(idOrSlug));
    }

    /**
     * The build an install would fetch: the newest stable one, or the newest of anything when the
     * project has never published a stable build for this server.
     *
     * @param compatible what this server can run, newest first
     * @return the build to offer, or null when the list is empty
     */
    public static ModrinthVersion installTarget(List<ModrinthVersion> compatible) {

        for (ModrinthVersion version : compatible) {
            if (version.versionType() == ReleaseChannel.RELEASE) {
                return version;
            }
        }

        return compatible.isEmpty() ? null : compatible.get(0);
    }

    /**
     * Every build of a project this server could run, newest first, on any channel.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param idOrSlug the project to list
     * @return the compatible versions, newest published first
     */
    public List<ModrinthVersion> compatibleVersions(String idOrSlug) {

        ServerTarget target = target();

        for (List<String> tier : target.platform().loaderTiers()) {

            List<ModrinthVersion> versions;

            try {
                versions = modrinth.versions(idOrSlug, tier, target.gameVersions()).join();
            } catch (Exception e) {
                throw new InstallException("Could not reach Modrinth: " + rootMessage(e), e);
            }

            if (!versions.isEmpty()) {
                List<ModrinthVersion> sorted = new ArrayList<>(versions);
                sorted.sort(Comparator.comparing(ModrinthVersion::datePublished).reversed());
                return sorted;
            }
        }

        return List.of();
    }

    /**
     * Downloads an update and puts it in the update folder.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param candidate the update to stage
     */
    public void stage(UpdateCandidate candidate) {
        stage(candidate.plugin(), candidate.version());
    }

    /**
     * Downloads any build of an already-installed plugin and puts it in the update folder.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param plugin  the tracked plugin to replace
     * @param version the build to put in its place
     */
    public void stage(TrackedPlugin plugin, ModrinthVersion version) {

        Path staged = downloader.fetch(version, Runtime.version().feature());
        Path folder = getServer().getUpdateFolderFile().toPath();

        // The downloader already writes it under the file name Modrinth publishes it as.
        String published = staged.getFileName().toString();

        try {
            Files.createDirectories(folder);
            Files.move(staged, folder.resolve(published), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InstallException("Could not stage the build: " + e.getMessage(), e);
        }

        plugin.stagedAs(published);
        plugin.pendingRestart(true);
        saveTracking();
    }

    /**
     * Downloads a plugin that is not installed and puts it in the plugins folder.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param project the project being installed
     * @param version the build to install
     * @param channel the channel this plugin should follow from now on
     * @param by      who asked, for the audit trail
     * @return the new tracking record
     */
    public TrackedPlugin install(ModrinthProject project, ModrinthVersion version,
                                 ReleaseChannel channel, String by) {
        return install(List.of(new Pending(project, version, channel, true)), by).get(0);
    }

    /**
     * Installs several builds together, or none of them.
     *
     * @param pending what to install, in the order it should be recorded
     * @param by      who asked
     * @return the tracking records, in the same order
     * @throws InstallException if any download or check fails, having written nothing
     */
    public List<TrackedPlugin> install(List<Pending> pending, String by) {

        Map<Pending, Path> staged = new LinkedHashMap<>();

        for (Pending one : pending) {
            staged.put(one, downloader.fetch(one.version(), Runtime.version().feature()));
        }

        TrackingDefaults defaults = defaults();
        List<TrackedPlugin> installed = new ArrayList<>();

        for (Map.Entry<Pending, Path> entry : staged.entrySet()) {

            Pending one = entry.getKey();
            String hash = one.version().primaryFile().sha512();
            String fileName = entry.getValue().getFileName().toString();

            place(entry.getValue(), pluginsFolder().resolve(fileName), hash);

            TrackedPlugin tracked = TrackedPlugin.of(one.version(), fileName, hash,
                    one.channel(), by);

            tracked.name(one.project().title());
            tracked.slug(one.project().slug());
            tracked.autoUpdate(defaults.autoUpdate());
            tracked.explicit(one.explicit());
            tracked.pendingLoad(!stillRunning(hash));

            tracking.put(tracked);
            installed.add(tracked);
        }

        saveTracking();
        return installed;
    }

    /**
     * A build about to be installed.
     *
     * @param explicit false when it is only here because something else named it, which is what
     *                 lets a later autoremove offer it once nothing needs it
     */
    public record Pending(ModrinthProject project, ModrinthVersion version, ReleaseChannel channel,
                          boolean explicit) {
    }

    /**
     * What a build depends on.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param version the build being considered
     * @return the resolution, with required dependencies followed through the whole graph
     */
    public DependencyResolver.Resolution dependenciesOf(ModrinthVersion version) {

        DependencyResolver resolver = new DependencyResolver(
                this::installTarget,
                projectId -> tracking.byProjectId(projectId) != null);

        return resolver.resolve(version);
    }

    /**
     * Puts a downloaded build into the plugins folder.
     *
     * <p>The hash is what makes that safe. A <em>different</em> build sharing the file name is a
     * genuine collision and still refused, because replacing a jar the server has open is what the
     * update folder exists for.</p>
     */
    private void place(Path staged, Path target, String sha512) {

        if (Files.exists(target)) {

            boolean sameBuild = sha512 != null && sha512.equalsIgnoreCase(hashOf(target));

            if (!sameBuild || !deleteAtShutdown.remove(target)) {
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

    /**
     * Moves a plugin's jar to the trash and stops tracking it.
     *
     * @param plugin the plugin to remove
     * @param by     who asked
     * @return what was binned, which identifies this exact removal so it can be undone, or null
     *         if there was no file to bin
     */
    public TrashBin.Result uninstall(TrackedPlugin plugin, String by) {

        Path jar = pluginsFolder().resolve(plugin.fileName());
        TrashBin.Result result = null;

        if (Files.isRegularFile(jar)) {

            result = trash.bin(jar, plugin, by);

            if (!result.deleted()) {
                deleteAtShutdown.add(jar);
            }
        }

        discardStagedUpdate(plugin);

        tracking.remove(plugin.projectId());
        saveTracking();

        return result;
    }

    /**
     * Everything currently in the trash, newest removal first.
     */
    public List<TrashEntry> trashed() {
        return trash.list();
    }

    /**
     * One removal by the name it is filed under.
     *
     * @param storedAs the id carried by an undo button
     * @return the entry, or null if it has already been restored or pruned
     */
    public TrashEntry trashed(String storedAs) {
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
    public TrackedPlugin restore(TrashEntry entry, String by) {

        if (entry.projectId() != null && tracking.byProjectId(entry.projectId()) != null) {
            throw new InstallException(entry.displayName() + " is already installed.");
        }

        Path target = pluginsFolder().resolve(entry.fileName());

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
     * server what is loaded: nothing is ever unloaded without one. A removal from this session left
     * the plugin running, so the jar is back before anything noticed it had gone. An older one did
     * not survive the restart in between.</p>
     */
    private TrackedPlugin track(TrashEntry entry, String by) {

        if (entry.projectId() == null) {
            return null;
        }

        TrackingDefaults defaults = defaults();

        TrackedPlugin tracked = new TrackedPlugin();

        tracked.projectId(entry.projectId());
        tracked.slug(entry.slug());
        tracked.name(entry.name());
        tracked.versionId(entry.versionId());
        tracked.versionNumber(entry.versionNumber());
        tracked.fileName(entry.fileName());
        tracked.sha512(entry.sha512());
        tracked.channel(entry.channel() == null ? defaults.channel() : entry.channel());
        tracked.autoUpdate(defaults.autoUpdate());
        tracked.installedBy(by);
        tracked.installedAt(Instant.now());
        tracked.pendingLoad(!removedWhileRunning(entry.removedAt()));

        tracking.put(tracked);
        saveTracking();

        return tracked;
    }

    /**
     * Whether a build that has just been written to the plugins folder is already loaded.
     */
    private boolean stillRunning(String sha512) {

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
     * Deletes one removal permanently.
     *
     * @param entry what to delete
     */
    public void discardTrashed(TrashEntry entry) {
        trash.discard(entry);
    }

    /**
     * Deletes every removal permanently.
     *
     * @return how many went
     */
    public int emptyTrash() {
        return trash.empty();
    }

    /**
     * Drops removals older than the configured window.
     */
    private void pruneTrash() {

        int days = configuration.trash.retentionDays;

        if (days <= 0) {
            return;
        }

        int dropped = trash.prune(Duration.ofDays(days), Instant.now());

        if (dropped > 0) {
            Logger.debug(Action.SETUP, "Emptied " + dropped + " removals older than "
                    + days + " days from the trash.");
        }
    }

    /**
     * Deletes the jars this server would not let go of, once it has.
     */
    private void finishRemovals() {

        for (Path jar : deleteAtShutdown) {

            try {
                Files.deleteIfExists(jar);
            } catch (IOException ignored) {
                // There is no longer anywhere to report this to.
            }
        }
    }

    /**
     * Drops an update waiting in the update folder for a plugin that is being removed, so the
     * restart does not put the jar back.
     */
    private void discardStagedUpdate(TrackedPlugin plugin) {

        try {
            Files.deleteIfExists(getServer().getUpdateFolderFile().toPath()
                    .resolve(stagedName(plugin)));
        } catch (IOException e) {
            Logger.warn(Action.UPDATE, "A staged update for " + plugin.displayName()
                    + " is still in the update folder and should be deleted by hand.");
        }
    }

    /**
     * Changes which builds a plugin will accept from now on.
     *
     * @param plugin  the plugin to change
     * @param channel the least stable channel it should accept
     */
    public void setChannel(TrackedPlugin plugin, ReleaseChannel channel) {
        saving(() -> settings.channel(plugin, channel));
    }

    /**
     * Decides whether Catalog may update this plugin without being asked.
     *
     * @param plugin the plugin to change
     * @param on     true to let it update itself
     */
    public void setAutoUpdate(TrackedPlugin plugin, boolean on) {
        saving(() -> settings.autoUpdate(plugin, on));
    }

    /**
     * Sets how long a build must have been public before this plugin will take it unattended.
     *
     * @param plugin  the plugin to change
     * @param minutes the window, or {@link TrackedPlugin#INHERIT_SOAK} to follow the config
     */
    public void setSoak(TrackedPlugin plugin, int minutes) {
        saving(() -> settings.soak(plugin, minutes));
    }

    /**
     * @return the soak window plugins fall back to when they follow the config
     */
    public int defaultSoakMinutes() {
        return settings.defaultSoakMinutes();
    }

    /**
     * Freezes a plugin at the version it has now, or lets it move again.
     *
     * @param plugin the plugin to hold
     * @param held   true to freeze it
     */
    public void setHeld(TrackedPlugin plugin, boolean held) {
        saving(() -> settings.held(plugin, held));
    }

    /**
     * The loaders this server can use, for showing which of a project's loaders apply here.
     *
     * @return the loader names, most specific first
     */
    public List<String> platformLoaders() {
        return target().loaders();
    }

    /**
     * The jar Catalog is running from.
     *
     * @return the file name of Catalog's own jar
     */
    public String ownFileName() {
        return getFile().getName();
    }

    /**
     * @param plugin the tracked plugin to test
     * @return true if this record is Catalog itself
     */
    public boolean isSelf(TrackedPlugin plugin) {
        return plugin != null && ownFileName().equals(plugin.fileName());
    }

    /**
     * @return the exact Minecraft version this server runs
     */
    public String gameVersion() {
        return getServer().getMinecraftVersion();
    }

    /**
     * Every build a project has ever published, newest first, not filtered.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param idOrSlug the project to list
     * @return every version, newest published first
     */
    public List<ModrinthVersion> allVersions(String idOrSlug) {

        List<ModrinthVersion> versions;

        try {
            versions = new ArrayList<>(modrinth.versions(idOrSlug, null, null).join());
        } catch (Exception e) {
            throw new InstallException("Could not reach Modrinth: " + rootMessage(e), e);
        }

        versions.sort(Comparator.comparing(ModrinthVersion::datePublished).reversed());
        return versions;
    }

    private void saveTracking() {
        saving(() -> tracking.save());
    }

    /**
     * Runs core work that writes the tracking file, reporting a failure rather than throwing it.
     */
    private void saving(Save work) {

        try {
            work.run();
        } catch (TrackingException e) {
            Logger.error(Action.TRACK, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface Save {
        void run() throws TrackingException;
    }

    /**
     * Searches Modrinth, narrowed to what this server could actually run.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param query the search text
     * @param limit how many results to return
     * @return one page of results
     */
    public SearchResults search(String query, int limit, int offset) {

        ServerTarget target = target();
        List<List<String>> facets = new ArrayList<>();

        facets.add(List.of("project_type:plugin"));

        List<String> loaders = new ArrayList<>();

        for (String loader : target.loaders()) {
            loaders.add("categories:" + loader);
        }

        facets.add(loaders);
        facets.add(List.of("versions:" + target.gameVersion()));

        return modrinth.search(query, facets, limit, offset).join();
    }

    /**
     * Looks a project up by its id or slug.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param idOrSlug what to look for
     * @return the project, or null if there is no such thing
     */
    public ModrinthProject project(String idOrSlug) {

        try {
            return modrinth.project(idOrSlug).join();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Describes this server the way Modrinth needs to be asked.
     */
    private ServerTarget target() {

        return ServerTarget.builder()
                .platform(detectPlatform())
                .gameVersion(getServer().getMinecraftVersion())
                .javaVersion(Runtime.version().feature())
                .build();
    }

    private static ServerPlatform detectPlatform() {

        if (isPresent("io.papermc.paper.threadedregions.RegionizedServer")) {
            return ServerPlatform.FOLIA;
        }

        if (isPresent("org.purpurmc.purpur.PurpurConfig")) {
            return ServerPlatform.PURPUR;
        }

        return ServerPlatform.PAPER;
    }

    private static boolean isPresent(String className) {

        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Fills in the human names of tracked plugins that only have a project id.
     *
     * @return true if anything was named, so the caller knows to save
     */
    private boolean nameTrackedPlugins() {

        List<String> missing = new ArrayList<>();

        for (TrackedPlugin plugin : tracking.all()) {
            if (plugin.name() == null && plugin.projectId() != null) {
                missing.add(plugin.projectId());
            }
        }

        if (missing.isEmpty()) {
            return false;
        }

        try {

            for (ModrinthProject project : modrinth.projects(missing).join()) {

                TrackedPlugin plugin = tracking.byProjectId(project.id());

                if (plugin != null) {
                    plugin.name(project.title());
                    plugin.slug(project.slug());
                }
            }

            return true;

        } catch (Exception e) {
            // Names are cosmetic; project ids still identify everything correctly without them.
            Logger.debug(Action.TRACK, "Could not fetch project names: " + rootMessage(e));
            return false;
        }
    }

    /**
     * Says what the scan found, at the volume each outcome deserves.
     */
    private void describe(ReconcileReport report, ScanResult scan) {

        Logger.info(Action.SCAN, "Indexed " + scan.jars().size() + " jars, tracking "
                + tracking.size() + " plugins.");

        if (!report.adopted().isEmpty()) {
            Logger.info(Action.TRACK, "Adopted " + report.adopted().size()
                    + " plugins: " + names(report.adopted()));
        }

        if (!report.applied().isEmpty()) {
            Logger.info(Action.UPDATE, "Updates applied on this start: " + names(report.applied()));
        }

        for (TrackedPlugin plugin : report.notApplied()) {
            Logger.warn(Action.UPDATE, plugin.displayName() + " is still "
                    + plugin.versionNumber() + ": the staged build was not taken from "
                    + getServer().getUpdateFolderFile().getName()
                    + ". It is still there and will be tried again on the next start.");
        }

        if (!report.moved().isEmpty()) {
            Logger.info(Action.TRACK, "Replaced by hand since last start: " + names(report.moved()));
        }

        if (!report.renamed().isEmpty()) {
            Logger.debug(Action.TRACK, "Renamed by hand since last start: " + names(report.renamed()));
        }

        if (!report.removed().isEmpty()) {
            Logger.info(Action.TRACK, "No longer installed, so no longer tracked: "
                    + names(report.removed()));
        }

        if (!report.unknown().isEmpty()) {
            Logger.debug(Action.TRACK, report.unknown().size()
                    + " jars are not on Modrinth and will be left alone.");
        }

        if (!report.notAdopted().isEmpty()) {
            Logger.info(Action.TRACK, report.notAdopted().size()
                    + " recognised plugins were not adopted because auto_track is off.");
        }

        for (TrackedPlugin plugin : report.orphaned()) {
            Logger.warn(Action.TRACK, plugin.displayName()
                    + " was replaced with a different plugin, so Catalog stopped tracking it.");
        }

        for (InstalledJar jar : report.conflicting()) {
            Logger.warn(Action.TRACK, jar.fileName()
                    + " is a second jar for a project that is already tracked.");
        }
    }

    private TrackingDefaults defaults() {

        Config.Tracking.Defaults configured = configuration.tracking.defaults;

        return TrackingDefaults.builder()
                .channel(configured.channel)
                .autoUpdate(configured.autoUpdate)
                .soakMinutes(configured.soakMinutes)
                .build();
    }

    /**
     * The folder Catalog manages, which is the parent of its own data folder.
     *
     * @return the plugins folder
     */
    public Path pluginsFolder() {
        return getDataFolder().getParentFile().toPath();
    }

    public PlatformScheduler getScheduler() {
        return foliaLib.getScheduler();
    }

    private static String names(List<TrackedPlugin> plugins) {

        List<String> names = new ArrayList<>();

        for (TrackedPlugin plugin : plugins) {
            names.add(plugin.displayName());
        }

        return String.join(", ", names);
    }

    /**
     * The message worth showing, since a failed future wraps the real cause.
     */
    private static String rootMessage(Throwable error) {

        Throwable cause = error;

        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }

        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static boolean hasPaperApi() {

        try {
            Class.forName("net.kyori.adventure.text.minimessage.MiniMessage");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

}
