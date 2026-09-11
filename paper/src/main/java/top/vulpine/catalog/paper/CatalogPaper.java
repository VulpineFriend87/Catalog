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
import top.vulpine.catalog.install.DependencyResolver;
import top.vulpine.catalog.install.Downloader;
import top.vulpine.catalog.install.Installer;
import top.vulpine.catalog.install.InstallException;
import top.vulpine.catalog.modrinth.ModrinthClient;
import top.vulpine.catalog.modrinth.Projects;
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
import top.vulpine.catalog.platform.Platform;
import top.vulpine.catalog.tracking.Dependents;
import top.vulpine.catalog.tracking.IgnoreList;
import top.vulpine.catalog.tracking.Library;
import top.vulpine.catalog.tracking.Settings;
import top.vulpine.catalog.tracking.TrackingException;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;
import top.vulpine.catalog.trash.Removals;
import top.vulpine.catalog.trash.TrashBin;
import top.vulpine.catalog.trash.model.TrashEntry;
import top.vulpine.catalog.update.Updates;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Catalog for Paper, Purpur, and Folia.
 */
@Getter
public final class CatalogPaper extends JavaPlugin implements Platform {

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
    private Projects projects;
    private Removals removals;
    private Installer installer;
    private Updates updates;
    private Library library;
    private Dependents dependents;

    /**
     * When this server came up.
     */
    private final Instant startedAt = Instant.now();


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
        this.projects = new Projects(this, modrinth, tracking);
        this.removals = new Removals(this, trash, tracking, this::defaults, startedAt);
        this.installer = new Installer(this, downloader, tracking, removals, this::defaults);
        this.updates = new Updates(this, modrinth, tracking, installer,
                () -> configuration.tracking.defaults.soakMinutes, projects::dependenciesOf);
        this.library = new Library(this, modrinth, tracking, ignored, this::defaults,
                () -> configuration.tracking.autoTrack);
        this.dependents = new Dependents(tracking, hashes -> modrinth.identify(hashes).join());

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

        if (library.index()) {
            checkForUpdates();
        }
    }

    private void checkForUpdates() {
        updates.check();
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

        try {
            return updates.refresh();
        } catch (TrackingException e) {
            Logger.error(Action.TRACK, e.getMessage());
            return updates.open();
        }
    }

    public List<UpdateCandidate> updates() {
        return updates.open();
    }

    public Map<String, UpdateCandidate> updatesByProject() {
        return updates.byProject();
    }

    public List<DependencyResolver.Requirement> missingFor(ModrinthVersion version) {
        return updates.missingFor(version);
    }

    /**
     * Drops a staged build so the next restart leaves the plugin as it is.
     *
     * @param plugin the plugin to leave alone
     * @return false when the staged file could not be deleted
     */
    /**
     * The installed plugins that require this one.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param plugin the plugin about to be removed
     * @return what would be left without a dependency
     */
    public List<TrackedPlugin> dependentsOf(TrackedPlugin plugin) {
        return dependents.of(plugin);
    }

    public boolean cancelUpdate(TrackedPlugin plugin) {

        try {
            return installer.cancel(plugin);
        } catch (TrackingException e) {
            Logger.error(Action.TRACK, e.getMessage());
            return false;
        }
    }

    public ModrinthVersion installTarget(String idOrSlug) {
        return projects.installTarget(idOrSlug);
    }

    public static ModrinthVersion installTarget(List<ModrinthVersion> compatible) {
        return Projects.installTarget(compatible);
    }

    public List<ModrinthVersion> compatibleVersions(String idOrSlug) {
        return projects.compatibleVersions(idOrSlug);
    }

    public void stage(UpdateCandidate candidate) {
        saving(() -> installer.stage(candidate));
    }

    public void stage(TrackedPlugin plugin, ModrinthVersion version) {
        saving(() -> installer.stage(plugin, version));
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

        List<Installer.Pending> work = new ArrayList<>();

        for (Pending one : pending) {
            work.add(new Installer.Pending(one.project(), one.version(), one.channel(),
                    one.explicit()));
        }

        try {
            return installer.install(work, by);
        } catch (TrackingException e) {
            throw new InstallException(e.getMessage(), e);
        }
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

    public DependencyResolver.Resolution dependenciesOf(ModrinthVersion version) {
        return projects.dependenciesOf(version);
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

        if (!removals.cancelStagedFor(plugin)) {
            Logger.warn(Action.UPDATE, "A staged update for " + plugin.displayName()
                    + " is still in the update folder and should be deleted by hand.");
        }

        try {
            return removals.uninstall(plugin, by);
        } catch (TrackingException e) {
            throw new InstallException(e.getMessage(), e);
        }
    }

    public List<TrashEntry> trashed() {
        return removals.list();
    }

    public TrashEntry trashed(String storedAs) {
        return removals.find(storedAs);
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

        try {
            return removals.restore(entry, by);
        } catch (TrackingException e) {
            throw new InstallException(e.getMessage(), e);
        }
    }

    public void discardTrashed(TrashEntry entry) {
        removals.discard(entry);
    }

    public int emptyTrash() {
        return removals.empty();
    }

    /**
     * Drops removals older than the configured window.
     */
    private void pruneTrash() {

        int days = configuration.trash.retentionDays;
        int dropped = removals.prune(days);

        if (dropped > 0) {
            Logger.debug(Action.SETUP, "Emptied " + dropped + " removals older than "
                    + days + " days from the trash.");
        }
    }

    private void finishRemovals() {
        removals.finish();
    }

    public void setChannel(TrackedPlugin plugin, ReleaseChannel channel) {
        saving(() -> settings.channel(plugin, channel));
    }

    public void setAutoUpdate(TrackedPlugin plugin, boolean on) {
        saving(() -> settings.autoUpdate(plugin, on));
    }

    public void setSoak(TrackedPlugin plugin, int minutes) {
        saving(() -> settings.soak(plugin, minutes));
    }

    public int defaultSoakMinutes() {
        return settings.defaultSoakMinutes();
    }

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

    public List<ModrinthVersion> allVersions(String idOrSlug) {
        return projects.allVersions(idOrSlug);
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

    public SearchResults search(String query, int limit, int offset) {
        return projects.search(query, limit, offset);
    }

    public ModrinthProject project(String idOrSlug) {
        return projects.project(idOrSlug);
    }

    @Override
    public ServerTarget target() {

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

    @Override
    public String stagingName() {
        return getServer().getUpdateFolderFile().getName();
    }

    @Override
    public void applyAtRestart(Path staged, String fileName) {

        Path folder = getServer().getUpdateFolderFile().toPath();

        try {
            Files.createDirectories(folder);
            Files.move(staged, folder.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InstallException("Could not stage the build: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isStaged(String fileName) {
        return Files.exists(getServer().getUpdateFolderFile().toPath().resolve(fileName));
    }

    @Override
    public boolean cancelStaged(String fileName) {

        try {
            Files.deleteIfExists(getServer().getUpdateFolderFile().toPath().resolve(fileName));
            return true;
        } catch (IOException e) {
            return false;
        }
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
