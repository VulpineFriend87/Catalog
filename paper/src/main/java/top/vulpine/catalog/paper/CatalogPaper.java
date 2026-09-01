package top.vulpine.catalog.paper;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.yaml.bukkit.YamlBukkitConfigurer;
import eu.okaeri.configs.yaml.bukkit.serdes.SerdesBukkit;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;
import top.vulpine.catalog.jar.JarScanner;
import top.vulpine.catalog.jar.model.InstalledJar;
import top.vulpine.catalog.jar.model.ScanResult;
import top.vulpine.catalog.modrinth.ModrinthClient;
import top.vulpine.catalog.modrinth.model.ModrinthProject;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.paper.config.Config;
import top.vulpine.catalog.tracking.IgnoreList;
import top.vulpine.catalog.tracking.Reconciler;
import top.vulpine.catalog.tracking.TrackingException;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.ReconcileReport;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;
import top.vulpine.commons.log.LogAction;
import top.vulpine.commons.log.Logger;
import top.vulpine.commons.text.Colorize;
import top.vulpine.commons.text.Dialect;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private enum Action implements LogAction {
        CONFIG, SETUP, SCAN, TRACK
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

        describe(report, scan);
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
