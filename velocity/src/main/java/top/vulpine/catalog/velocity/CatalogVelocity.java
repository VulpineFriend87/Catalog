package top.vulpine.catalog.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.yaml.snakeyaml.YamlSnakeYamlConfigurer;
import lombok.Getter;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandPermission;
import revxrsal.commands.velocity.VelocityLamp;
import revxrsal.commands.velocity.VelocityVisitors;
import revxrsal.commands.velocity.actor.VelocityCommandActor;
import top.vulpine.catalog.history.History;
import top.vulpine.catalog.install.Downloader;
import top.vulpine.catalog.install.InstallException;
import top.vulpine.catalog.install.Installer;
import top.vulpine.catalog.modrinth.ModrinthClient;
import top.vulpine.catalog.modrinth.Projects;
import top.vulpine.catalog.platform.ExitTasks;
import top.vulpine.catalog.platform.Platform;
import top.vulpine.catalog.tracking.IgnoreList;
import top.vulpine.catalog.tracking.Library;
import top.vulpine.catalog.tracking.TrackingException;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;
import top.vulpine.catalog.trash.Removals;
import top.vulpine.catalog.trash.TrashBin;
import top.vulpine.catalog.update.Updates;
import top.vulpine.catalog.update.model.ServerPlatform;
import top.vulpine.catalog.update.model.ServerTarget;
import top.vulpine.catalog.velocity.command.ConsoleCommand;
import top.vulpine.catalog.velocity.config.Config;
import top.vulpine.commons.log.LogAction;
import top.vulpine.commons.log.LogLevel;
import top.vulpine.commons.log.Logger;
import top.vulpine.commons.text.Colorize;
import top.vulpine.commons.text.Dialect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Catalog for Velocity.
 */
@Getter
public final class CatalogVelocity implements Platform {

    private static final String USER_AGENT = "VulpineFriend87/Catalog/";

    private final ProxyServer server;
    private final ComponentLogger componentLogger;
    private final Path dataDirectory;
    private final PluginContainer container;

    private final Instant startedAt = Instant.now();

    private Config configuration;
    private ModrinthClient modrinth;
    private TrackingStore tracking;
    private IgnoreList ignored;
    private Downloader downloader;
    private History history;
    private Projects projects;
    private ExitTasks exitTasks;
    private Removals removals;
    private Installer installer;
    private Updates updates;
    private Library library;

    private enum Action implements LogAction {
        CONFIG, SETUP, UPDATE
    }

    @Inject
    public CatalogVelocity(ProxyServer server, ComponentLogger componentLogger,
                           @DataDirectory Path dataDirectory, PluginContainer container) {
        this.server = server;
        this.componentLogger = componentLogger;
        this.dataDirectory = dataDirectory.toAbsolutePath();
        this.container = container;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {

        Colorize.init(Dialect.MODERN);
        Logger.builder().logger(componentLogger).build();

        if (!loadConfiguration()) {
            return;
        }

        this.modrinth = ModrinthClient.builder()
                .userAgent(USER_AGENT + version() + " (vulpine.top)")
                .token(configuration.modrinth.token)
                .cacheDirectory(dataDirectory.resolve("cache"))
                .build();

        Path data = dataDirectory.resolve("data");

        this.tracking = new TrackingStore(data.resolve("tracked.json"));
        this.ignored = new IgnoreList(data.resolve("ignored.json"));

        try {
            tracking.load();
            ignored.load();
        } catch (TrackingException e) {
            Logger.error(Action.CONFIG, e.getMessage());
            return;
        }

        this.downloader = new Downloader(modrinth, dataDirectory.resolve("staging"));
        this.history = new History(data.resolve("history.json"));
        this.history.load();

        TrashBin trash = new TrashBin(dataDirectory.resolve("trash"));

        this.projects = new Projects(this, modrinth, tracking);
        this.exitTasks = new ExitTasks(dataDirectory,
                container.getDescription().getSource().map(Path::toAbsolutePath).orElse(null));
        this.exitTasks.report();

        this.removals = new Removals(this, trash, tracking, this::defaults, startedAt, history,
                exitTasks);
        this.installer = new Installer(this, downloader, tracking, removals, this::defaults, history);
        this.updates = new Updates(this, modrinth, tracking, installer,
                () -> configuration.tracking.defaults.soakMinutes, projects::dependenciesOf,
                history, () -> library.index());
        this.library = new Library(this, modrinth, tracking, ignored, this::defaults,
                () -> configuration.tracking.autoTrack, history);

        registerCommands();

        // Velocity has no update folder. A downloaded build waits in Catalog's own folder and is
        // moved over the installed jar as the JVM exits.
        Runtime.getRuntime().addShutdownHook(new Thread(this::finishShutdown, "Catalog exit tasks"));

        server.getScheduler().buildTask(this, this::index).schedule();
        server.getScheduler().buildTask(this, () -> removals.prune(configuration.trash.retentionDays))
                .schedule();
        scheduleUpdateChecks();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {

        if (modrinth != null) {
            modrinth.close();
        }

        Logger.close();
    }

    private void registerCommands() {

        CommandPermission<VelocityCommandActor> console = VelocityCommandActor::isConsole;

        Lamp<VelocityCommandActor> lamp = VelocityLamp.builder(this, server)
                .permissionFactory((annotations, built) -> console)
                .build();

        lamp.register(new ConsoleCommand(this));
        lamp.accept(VelocityVisitors.brigadier(server));
    }

    private boolean loadConfiguration() {

        try {
            configuration = ConfigManager.create(Config.class, it -> {
                it.withConfigurer(new YamlSnakeYamlConfigurer());
                it.withBindFile(dataDirectory.resolve("config.yml"));
                it.saveDefaults();
                it.load(true);
            });
        } catch (Exception e) {
            Logger.error(Action.CONFIG, "Failed to load configuration: " + e.getMessage());
            return false;
        }

        Logger.builder()
                .logger(componentLogger)
                .level(configuration.logLevel)
                .trace(configuration.logLevel == LogLevel.DEBUG ? dataDirectory.toFile() : null)
                .build();

        return true;
    }

    private void index() {

        downloader.clean();

        if (library.index()) {
            updates.check();
        }
    }

    private void scheduleUpdateChecks() {

        int minutes = configuration.updates.checkIntervalMinutes;

        if (minutes <= 0) {
            return;
        }

        server.getScheduler().buildTask(this, updates::check)
                .delay(minutes, TimeUnit.MINUTES)
                .repeat(minutes, TimeUnit.MINUTES)
                .schedule();
    }

    /**
     * Queues every downloaded build over the jar it replaces, then does the exit tasks.
     */
    private void finishShutdown() {

        for (TrackedPlugin plugin : tracking.pendingRestart()) {
            if (plugin.stagedAs() != null) {
                exitTasks.replace(updateFolder().resolve(plugin.stagedAs()),
                        pluginsFolder().resolve(plugin.fileName()),
                        pluginsFolder().resolve(plugin.stagedAs()));
            }
        }

        exitTasks.run();
    }

    public String version() {
        return container.getDescription().getVersion().orElse("unknown");
    }

    public TrackingDefaults defaults() {

        Config.Tracking.Defaults configured = configuration.tracking.defaults;

        return TrackingDefaults.builder()
                .channel(configured.channel)
                .autoUpdate(configured.autoUpdate)
                .soakMinutes(configured.soakMinutes)
                .build();
    }

    private Path updateFolder() {
        return dataDirectory.resolve("update");
    }

    @Override
    public Path pluginsFolder() {
        return dataDirectory.getParent();
    }

    @Override
    public ServerTarget target() {

        return ServerTarget.builder()
                .platform(ServerPlatform.VELOCITY)
                .javaVersion(Runtime.version().feature())
                .build();
    }

    @Override
    public String ownFileName() {
        return container.getDescription().getSource()
                .map(path -> path.getFileName().toString())
                .orElse("");
    }

    @Override
    public String stagingName() {
        return pluginsFolder().getParent().relativize(updateFolder()).toString();
    }

    @Override
    public void applyAtRestart(Path staged, String fileName) {

        try {
            Files.createDirectories(updateFolder());
            Files.move(staged, updateFolder().resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InstallException("Could not prepare the update: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isStaged(String fileName) {
        return Files.exists(updateFolder().resolve(fileName));
    }

    @Override
    public boolean cancelStaged(String fileName) {

        try {
            Files.deleteIfExists(updateFolder().resolve(fileName));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

}
