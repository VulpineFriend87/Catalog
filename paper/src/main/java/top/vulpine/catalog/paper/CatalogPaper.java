package top.vulpine.catalog.paper;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.yaml.bukkit.YamlBukkitConfigurer;
import eu.okaeri.configs.yaml.bukkit.serdes.SerdesBukkit;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.BukkitLamp;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import top.vulpine.catalog.hash.Hashing;
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
import top.vulpine.catalog.paper.command.MainCommand;
import top.vulpine.catalog.paper.command.annotation.RequiresPermission;
import top.vulpine.catalog.paper.config.Config;
import top.vulpine.catalog.paper.util.PermissionChecker;
import top.vulpine.catalog.tracking.IgnoreList;
import top.vulpine.catalog.tracking.Reconciler;
import top.vulpine.catalog.tracking.TrackingException;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.ReconcileReport;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;
import top.vulpine.catalog.trash.TrashBin;
import top.vulpine.catalog.update.UpdateChecker;
import top.vulpine.catalog.update.model.ServerPlatform;
import top.vulpine.catalog.update.model.ServerTarget;
import top.vulpine.catalog.update.model.UpdateCandidate;
import top.vulpine.commons.log.LogAction;
import top.vulpine.commons.log.Logger;
import top.vulpine.commons.text.Colorize;
import top.vulpine.commons.text.Dialect;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog on Paper, Purpur and Folia.
 *
 * <p>Wires the platform to the core and gets out of the way: the plugins folder is scanned, every
 * hash is identified against Modrinth in one request, and the result is reconciled with what
 * Catalog remembered. All of it off the main thread.</p>
 */
@Getter
public final class CatalogPaper extends JavaPlugin {

    private static final String MODRINTH = "https://modrinth.com/plugin/catalog";

    private Config configuration;
    private FoliaLib foliaLib;
    private ModrinthClient modrinth;
    private TrackingStore tracking;
    private IgnoreList ignored;
    private Downloader downloader;
    private TrashBin trash;

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

        Lamp<BukkitCommandActor> lamp = BukkitLamp.builder(this)
                .permissionForAnnotation(RequiresPermission.class, annotation ->
                        actor -> PermissionChecker.hasPermission(actor.sender(), annotation.value()))
                .build();

        ClickContext clicks = new ClickContext();
        getServer().getPluginManager().registerEvents(clicks, this);

        lamp.register(new MainCommand(this, clicks));

        getScheduler().runAsync(task -> index());
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

    /**
     * Reads the config and applies the log level it asks for.
     *
     * @return false if the config could not be read
     */
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

        Logger.setLevel(configuration.logLevel);
        return true;
    }

    /**
     * Loads the tracking state.
     *
     * <p>A failure here disables the plugin rather than starting from an empty state. Tracking is
     * what tells Catalog which jars it is responsible for, and carrying on without it would risk
     * acting on the wrong file.</p>
     *
     * @return false if the state could not be read
     */
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

    /**
     * Scans the plugins folder, identifies everything against Modrinth and reconciles the result.
     *
     * <p>Runs off the main thread, and blocking on the lookup here is deliberate: this method is
     * already on an async thread and reading it in a straight line is worth more than chaining
     * callbacks.</p>
     */
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

    /**
     * Asks Modrinth what is out of date, so the answer is ready before anyone asks for it.
     *
     * <p>Kept out of the console on purpose. Startup already scrolls past, and a list of updates
     * printed there is read once and then ignored forever — {@code /catalog list} is where it
     * belongs, current at the moment it is asked for. Only a failure is worth saying out loud.</p>
     */
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
    }

    /**
     * Asks Modrinth what is out of date and remembers the answer.
     *
     * <p>Kept here rather than in the command so the startup check and {@code /catalog check} share
     * one path, and so the list command can annotate without going near the network.</p>
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @return the available updates
     */
    public List<UpdateCandidate> refreshUpdates() {

        noticeStagedApplied();

        ServerTarget target = target();
        Logger.debug(Action.UPDATE, "Checking against " + target + ", asking for loaders "
                + String.join(", ", target.loaders()) + ".");

        lastCheck = new UpdateChecker(modrinth, tracking).check(target);
        checkedAt = Instant.now();

        return lastCheck;
    }

    /**
     * Notices a staged build that something else already applied, mid-session.
     *
     * <p>Paper consumes the update folder in {@code FileProviderSource#checkUpdate}, which runs for
     * every plugin file it loads — not only during the startup scan. So a reload tool loading a
     * single plugin applies whatever Catalog staged for it, there and then. That is a fine outcome
     * and Catalog has no say in it; what it must not do is keep insisting a restart is owed for a
     * build that is already running.</p>
     *
     * <p>Cheap because it only looks at plugins actually marked staged, which is almost always
     * none. Blocks, so it must be called off the main thread.</p>
     */
    private void noticeStagedApplied() {

        Path updates = getServer().getUpdateFolderFile().toPath();
        Map<String, TrackedPlugin> rehashed = new HashMap<>();
        List<TrackedPlugin> abandoned = new ArrayList<>();

        for (TrackedPlugin plugin : tracking.pendingRestart()) {

            // Still sitting there waiting for a restart, which is the normal case.
            if (Files.exists(updates.resolve(plugin.fileName()))) {
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

            Logger.info(Action.UPDATE, plugin.displayName() + " is now "
                    + became.versionNumber() + ", applied without a restart by something else.");
        }
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
     * The newest build of a project this server could actually run.
     *
     * <p>Walks the same loader ladder the update check uses, so a plugin published only for an
     * older platform is still found, and a build for this exact server software always wins over a
     * newer one meant for its parent.</p>
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param idOrSlug the project to look at
     * @param channel  the least stable channel to accept
     * @return the version, or null if the project publishes nothing for this server
     */
    public ModrinthVersion newestCompatible(String idOrSlug, ReleaseChannel channel) {

        for (ModrinthVersion version : compatibleVersions(idOrSlug)) {
            if (version.versionType() == null || channel.accepts(version.versionType())) {
                return version;
            }
        }

        return null;
    }

    /**
     * Every build of a project this server could run, newest first, on any channel.
     *
     * <p>Deliberately unfiltered by channel. Which builds an operator is willing to run is their
     * decision to make in front of the list, not one to make silently on their behalf — a project
     * whose only build for this server is a beta is not the same thing as a project with no build
     * at all, and saying so was the bug this replaced.</p>
     *
     * <p>The loader ladder still applies: the first rung that answers wins, so a build for this
     * exact server software is never passed over for one meant for its parent.</p>
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
     * <p>Staged under the name the plugin already has on disk. Not because the name is what gets
     * matched — it is not, on either semantics: 1.18.2 and modern Paper both read the plugin name
     * out of the descriptor and search the update folder for it. Keeping the name is what stops the
     * jar being renamed underneath the tracking record, which finds a plugin by hash and then by
     * file name and by nothing else.</p>
     *
     * <p>The cost is that a versioned file name goes stale. Paper renames the installed jar to the
     * update file's name, so staging under the new build's real file name would fix that for free —
     * once reconciliation can survive a rename and a content change landing together.</p>
     *
     * <p>Nothing is loaded or unloaded here. The swap happens during the next startup, before any
     * plugin is enabled, which is the only moment it is safe.</p>
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
     * <p>Not only newer ones. Choosing a specific build is how someone walks back from a release
     * that broke their server, and refusing to go backwards would leave them doing it by hand —
     * which is the manual jar-swapping this plugin exists to replace.</p>
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param plugin  the tracked plugin to replace
     * @param version the build to put in its place
     */
    public void stage(TrackedPlugin plugin, ModrinthVersion version) {

        Path staged = downloader.fetch(version, Runtime.version().feature());
        Path folder = getServer().getUpdateFolderFile().toPath();

        try {
            Files.createDirectories(folder);
            Files.move(staged, folder.resolve(plugin.fileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InstallException("Could not stage the build: " + e.getMessage(), e);
        }

        plugin.pendingRestart(true);
        saveTracking();
    }

    /**
     * Downloads a plugin that is not installed and puts it in the plugins folder.
     *
     * <p>Written straight into place rather than through the update folder: there is nothing to
     * replace, so there is no file to be locked and no match to satisfy. It still only loads at the
     * next startup, because Catalog never enables a plugin on a running server.</p>
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

        Path staged = downloader.fetch(version, Runtime.version().feature());
        String fileName = staged.getFileName().toString();
        Path target = pluginsFolder().resolve(fileName);

        if (Files.exists(target)) {
            throw new InstallException(fileName + " already exists in the plugins folder.");
        }

        try {
            Files.move(staged, target);
        } catch (IOException e) {
            throw new InstallException("Could not write " + fileName + ": " + e.getMessage(), e);
        }

        TrackingDefaults defaults = defaults();

        TrackedPlugin tracked = TrackedPlugin.of(version, fileName,
                version.primaryFile().sha512(), channel, by);

        tracked.name(project.title());
        tracked.slug(project.slug());
        tracked.autoUpdate(defaults.autoUpdate());
        tracked.pendingLoad(true);

        tracking.put(tracked);
        saveTracking();

        return tracked;
    }

    /**
     * Moves a plugin's jar to the trash and stops tracking it.
     *
     * <p>The jar is copied before it is deleted, so a delete this JVM is not allowed to perform
     * costs nothing: on Windows the server holds every loaded jar open, and the removal is finished
     * on shutdown instead.</p>
     *
     * @param plugin the plugin to remove
     * @param by     who asked
     * @return true if the file is already gone, false if it goes at shutdown
     */
    public boolean uninstall(TrackedPlugin plugin, String by) {

        Path jar = pluginsFolder().resolve(plugin.fileName());
        boolean deleted = true;

        if (Files.isRegularFile(jar)) {

            TrashBin.Result result = trash.bin(jar, plugin, by);
            deleted = result.deleted();

            if (!deleted) {
                jar.toFile().deleteOnExit();
            }
        }

        discardStagedUpdate(plugin);

        tracking.remove(plugin.projectId());
        saveTracking();

        return deleted;
    }

    /**
     * Drops an update waiting in the update folder for a plugin that is being removed, so the
     * restart does not put the jar back.
     */
    private void discardStagedUpdate(TrackedPlugin plugin) {

        try {
            Files.deleteIfExists(getServer().getUpdateFolderFile().toPath()
                    .resolve(plugin.fileName()));
        } catch (IOException e) {
            Logger.warn(Action.UPDATE, "A staged update for " + plugin.displayName()
                    + " is still in the update folder and should be deleted by hand.");
        }
    }

    /**
     * Changes which builds a plugin will accept from now on.
     *
     * <p>Per plugin rather than global, because the reason for running a beta is always about one
     * specific plugin and never about the server.</p>
     *
     * @param plugin  the plugin to change
     * @param channel the least stable channel it should accept
     */
    public void setChannel(TrackedPlugin plugin, ReleaseChannel channel) {
        plugin.channel(channel);
        saveTracking();
    }

    /**
     * Freezes a plugin at the version it has now, or lets it move again.
     *
     * <p>Resolved to the concrete version rather than stored as "current", so the hold cannot
     * quietly drift if the jar is swapped by hand.</p>
     *
     * @param plugin the plugin to hold
     * @param held   true to freeze it
     */
    public void setHeld(TrackedPlugin plugin, boolean held) {

        if (held) {
            plugin.pinToCurrent();
        } else {
            plugin.pinnedVersionId(null);
        }

        saveTracking();
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
     * @return the exact Minecraft version this server runs
     */
    public String gameVersion() {
        return getServer().getMinecraftVersion();
    }

    /**
     * Every build a project has ever published, newest first, filtered by nothing at all.
     *
     * <p>Only reachable when the operator has turned incompatible installs on. Most of what comes
     * back will not load here, which is the entire point of the switch being off by default.</p>
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

        try {
            tracking.save();
        } catch (TrackingException e) {
            Logger.error(Action.TRACK, e.getMessage());
        }
    }

    /**
     * Searches Modrinth, narrowed to what this server could actually run.
     *
     * <p>Filtering by loader and game version at the source is what stops the results being a list
     * of things that would not load here.</p>
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
     *
     * <p>The Minecraft version has to be the exact one: a project can publish a single release as a
     * dozen Modrinth versions, each pinned to a few game versions, and asking loosely is how you end
     * up being offered a build that will not load.</p>
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
     * <p>Identification answers with versions, which carry a project id but no title, so a freshly
     * adopted plugin has nothing readable to call itself. One bulk request fixes every one of them,
     * and the names are then kept in the state file so this only happens once.</p>
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
