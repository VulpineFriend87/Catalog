package top.vulpine.catalog.paper.command;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Flag;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.Sized;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.annotation.Switch;
import top.vulpine.catalog.install.DependencyResolver;
import top.vulpine.catalog.modrinth.model.Dependency;
import top.vulpine.catalog.modrinth.model.DependencyType;
import top.vulpine.catalog.modrinth.model.ModrinthProject;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.modrinth.model.SearchHit;
import top.vulpine.catalog.modrinth.model.SearchResults;
import top.vulpine.catalog.modrinth.model.TeamMember;
import top.vulpine.catalog.paper.CatalogPaper;
import top.vulpine.catalog.paper.command.annotation.RequiresPermission;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.trash.TrashBin;
import top.vulpine.catalog.trash.model.TrashEntry;
import top.vulpine.catalog.update.model.UpdateCandidate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code /catalog} command.
 */
@Command({"catalog", "ctlg", "cata", "ctl", "clg"})
public final class MainCommand {

    /** How long a removal stays confirmable before it has to be asked for again. */
    private static final Duration CONFIRM_WINDOW = Duration.ofSeconds(30);

    /**
     * Word cap on a free-form argument that shares a command with a flag.
     *
     * <p>Lamp gives the last parameter the rest of the input, and flags have to be last, so a query
     * and {@code --page} cannot both be greedy. A capped list is non-greedy at registration and
     * still consumes every remaining word at parse time.</p>
     */
    private static final int MAX_WORDS = 12;

    private final CatalogPaper plugin;

    private final Map<String, Pending> confirmations = new ConcurrentHashMap<>();

    /** Which screen the button that ran this command was on. */
    private final ClickContext context;

    public MainCommand(CatalogPaper plugin, ClickContext context) {
        this.plugin = plugin;
        this.context = context;
    }

    @Description("Information about Catalog")
    @RequiresPermission("command.about")
    public void about(CommandSender sender) {

        List<String> authors = plugin.getDescription().getAuthors();

        send(sender, Messages.about(plugin.getDescription().getVersion(),
                authors.isEmpty() ? "Vulpine" : String.join(", ", authors)));
    }

    @Subcommand("help")
    @Description("Every command")
    @RequiresPermission("command.help")
    public void help(CommandSender sender) {
        send(sender, Messages.help());
    }

    @Subcommand("list")
    @Description("Managed plugins")
    @RequiresPermission("command.list")
    public void list(CommandSender sender) {
        plugin.getScheduler().runAsync(task -> showList(sender, true));
    }

    @Subcommand("info")
    @Description("Details for any plugin")
    @RequiresPermission("command.info")
    public void info(CommandSender sender,
                     @Named("plugin") @SuggestWith(Suggestions.Tracked.class) String query) {
        plugin.getScheduler().runAsync(task -> showProject(sender, query));
    }

    @Subcommand("search")
    @Description("Find plugins on Modrinth")
    @RequiresPermission("command.search")
    public void search(CommandSender sender, @Named("query") @Sized(max = MAX_WORDS) List<String> query,
                       @Flag("page") @Default("1") int page) {
        runSearch(sender, String.join(" ", query), Math.max(page, 1));
    }

    @Subcommand("install")
    @Description("Install a plugin from Modrinth")
    @RequiresPermission("command.install")
    public void install(CommandSender sender, @Named("plugin") @Single String query,
                        @Optional @Named("version") String wanted) {

        String data = context.take(sender);

        String named = wanted == null || ClickContext.strip(wanted).isEmpty()
                ? null : ClickContext.strip(wanted);

        plugin.getScheduler().runAsync(task -> {

            try {

                ModrinthProject project = plugin.project(query);

                if (project == null) {
                    send(sender, Messages.unknownPlugin(query));
                    return;
                }

                TrackedPlugin tracked = plugin.getTracking().byProjectId(project.id());

                if (tracked != null && named == null) {
                    send(sender, Messages.alreadyInstalled(tracked));
                    return;
                }

                if (tracked != null && tracked.isPinned()) {
                    send(sender, Messages.stillHeld(tracked.displayName()));
                    return;
                }

                List<ModrinthVersion> compatible = plugin.compatibleVersions(project.id());
                ModrinthVersion version = choose(compatible, named);

                if (version == null && named != null
                        && plugin.getConfiguration().allowIncompatibleInstalls) {
                    version = choose(plugin.allVersions(project.id()), named);
                }

                if (version == null) {
                    send(sender, named == null
                            ? Messages.noBuild(project.title())
                            : Messages.noVersion(project.title(), named));
                    return;
                }

                ReleaseChannel follow = version.versionType() == null
                        ? defaultChannel() : version.versionType();

                DependencyResolver.Resolution resolution = plugin.dependenciesOf(version);
                List<DependencyResolver.Requirement> missing = resolution.missing();
                String answer = intent(data);

                // A build that adds a dependency is checked whether it is a first install or a
                // switch. Only the switch asks twice, because it replaces a jar that already works.
                if ((!missing.isEmpty() || !resolution.conflicts().isEmpty()) && answer == null) {
                    showDependencies(sender, project, version, resolution, screen(data));
                    return;
                }

                if (tracked != null) {

                    if (!confirmed(sender, "switch:" + tracked.projectId() + ":" + version.id(), data)) {
                        send(sender, Messages.confirmSwitch(tracked, version,
                                isOlder(version, tracked), screen(data)));
                        return;
                    }

                    if (ClickContext.WITH_DEPENDENCIES.equals(answer)) {
                        plugin.install(pendingFor(missing), sender.getName());
                    }

                    plugin.setChannel(tracked, follow);
                    plugin.stage(tracked, version);

                    redraw(sender, screen(data));
                    send(sender, Messages.staged(tracked.displayName(), version.versionNumber()));
                    return;
                }

                List<CatalogPaper.Pending> pending = new ArrayList<>();

                if (ClickContext.WITH_DEPENDENCIES.equals(answer)) {
                    pending.addAll(pendingFor(missing));
                }

                pending.add(new CatalogPaper.Pending(project, version, follow, true));
                plugin.install(pending, sender.getName());

                redraw(sender, screen(data));
                send(sender, pending.size() == 1
                        ? Messages.installed(project.title(), version.versionNumber())
                        : Messages.installedWith(project.title(), version.versionNumber(),
                                pending.size() - 1));

            } catch (Exception e) {
                send(sender, Messages.failed(rootMessage(e)));
            }
        });
    }

    @Subcommand("versions")
    @Description("The newest release, beta and alpha for this server")
    @RequiresPermission("command.info")
    public void versions(CommandSender sender,
                         @Named("plugin") @Single @SuggestWith(Suggestions.Tracked.class) String query,
                         @Switch("all") boolean everything,
                         @Flag("page") @Default("1") int page) {

        String data = context.take(sender);

        abandonConfirmation(sender);

        plugin.getScheduler().runAsync(task -> {

            try {

                TrackedPlugin tracked = resolve(query);
                ModrinthProject project = plugin.project(tracked != null
                        ? tracked.projectId() : query);

                if (project == null) {
                    send(sender, Messages.unknownPlugin(query));
                    return;
                }

                TrackedPlugin installed = plugin.getTracking().byProjectId(project.id());
                boolean allowed = plugin.getConfiguration().allowIncompatibleInstalls;

                if (everything && allowed) {
                    send(sender, Messages.everyVersion(project, plugin.allVersions(project.id()),
                            installed, plugin.gameVersion(), page));
                    return;
                }

                send(sender, Messages.versions(project, plugin.gameVersion(),
                        newestOfEachChannel(plugin.compatibleVersions(project.id())),
                        installed, allowed, screen(data)));

            } catch (Exception e) {
                send(sender, Messages.unreachable(rootMessage(e)));
            }
        });
    }

    /**
     * The newest build of each channel.
     *
     * @param compatible the compatible builds, newest first
     * @return the newest of each channel, channels with none absent
     */
    private static Map<ReleaseChannel, ModrinthVersion> newestOfEachChannel(
            List<ModrinthVersion> compatible) {

        Map<ReleaseChannel, ModrinthVersion> newest = new EnumMap<>(ReleaseChannel.class);

        for (ModrinthVersion version : compatible) {
            if (version.versionType() != null) {
                newest.putIfAbsent(version.versionType(), version);
            }
        }

        return newest;
    }

    /**
     * Finds the build someone named.
     *
     * @param wanted the id or version number, or null to take what installing would default to
     */
    private static ModrinthVersion choose(List<ModrinthVersion> compatible, String wanted) {

        if (wanted == null) {
            return CatalogPaper.installTarget(compatible);
        }

        for (ModrinthVersion version : compatible) {
            if (wanted.equals(version.id()) || wanted.equalsIgnoreCase(version.versionNumber())) {
                return version;
            }
        }

        return null;
    }

    /**
     * Reloads config.yml.
     *
     * <p>Only the settings are reloaded and not the tracking state.</p>
     */
    @Subcommand("reload")
    @Description("Reload the configuration")
    @RequiresPermission("command.reload")
    public void reload(CommandSender sender) {

        send(sender, plugin.loadConfiguration()
                ? Messages.reloaded()
                : Messages.configFailed());
    }

    @Subcommand("settings")
    @Description("View the settings menu for a plugin")
    @RequiresPermission("command.settings")
    public void settings(CommandSender sender,
                         @Named("plugin") @SuggestWith(Suggestions.Tracked.class) String query) {

        TrackedPlugin tracked = resolve(query);

        if (tracked == null) {
            send(sender, Messages.unknownPlugin(query));
            return;
        }

        showSettings(sender, tracked, screen(context.take(sender)));
    }

    @Subcommand("auto")
    @Description("Whether a plugin updates itself automatically")
    @RequiresPermission("command.settings")
    public void auto(CommandSender sender,
                     @Named("plugin") @Single @SuggestWith(Suggestions.Tracked.class) String query,
                     @Named("state") Toggle state) {

        TrackedPlugin tracked = resolve(query);

        if (tracked == null) {
            send(sender, Messages.unknownPlugin(query));
            return;
        }

        plugin.setAutoUpdate(tracked, state.on());
        done(sender, context.take(sender), Messages.autoSet(tracked.displayName(), state.on()));
    }

    @Subcommand("soak")
    @Description("How long a build must be public before this plugin automatically installs it")
    @RequiresPermission("command.settings")
    public void soak(CommandSender sender,
                     @Named("plugin") @Single @SuggestWith(Suggestions.Tracked.class) String query,
                     @Named("window") String window) {

        TrackedPlugin tracked = resolve(query);

        if (tracked == null) {
            send(sender, Messages.unknownPlugin(query));
            return;
        }

        Integer minutes = parseSoak(window);

        if (minutes == null) {
            send(sender, Messages.badSoak());
            return;
        }

        plugin.setSoak(tracked, minutes);
        done(sender, context.take(sender), Messages.soakSet(tracked.displayName(), minutes,
                plugin.defaultSoakMinutes()));
    }

    /**
     * Reads a soak window.
     *
     * @param window minutes, or a value suffixed with m or h, or "default" to follow the config
     * @return the window in minutes, {@link TrackedPlugin#INHERIT_SOAK} for the default, or null if
     *         it could not be read
     */
    private static Integer parseSoak(String window) {

        String value = ClickContext.strip(window).trim().toLowerCase(Locale.ROOT);

        if (value.equals("default") || value.equals("inherit")) {
            return TrackedPlugin.INHERIT_SOAK;
        }

        int scale = value.endsWith("h") ? 60 : 1;

        if (value.endsWith("h") || value.endsWith("m")) {
            value = value.substring(0, value.length() - 1);
        }

        try {
            return Math.max(Integer.parseInt(value.trim()), 0) * scale;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reports what an action did, and redraws the screen it was taken on.
     *
     * @param data    what the button carried, or null when the command was typed
     * @param outcome the one line saying what happened
     */
    private void done(CommandSender sender, String data, Component outcome) {

        plugin.getScheduler().runAsync(task -> {
            redraw(sender, screen(data));
            send(sender, outcome);
        });
    }

    @Subcommand("channel")
    @Description("Which builds a plugin should follow")
    @RequiresPermission("command.channel")
    public void channel(CommandSender sender,
                        @Named("plugin") @Single @SuggestWith(Suggestions.Tracked.class) String query,
                        @Named("channel") ReleaseChannel channel) {

        TrackedPlugin tracked = resolve(query);

        if (tracked == null) {
            send(sender, Messages.unknownPlugin(query));
            return;
        }

        plugin.setChannel(tracked, channel);
        done(sender, context.take(sender), Messages.channelSet(tracked.displayName(), channel));
    }

    @Subcommand("update")
    @Description("Download an update and stage it for the next restart")
    @RequiresPermission("command.update")
    public void update(CommandSender sender,
                       @Named("plugin") @SuggestWith(Suggestions.Updatable.class) String query) {

        String wanted = ClickContext.strip(query);
        String data = context.take(sender);

        if (wanted.equalsIgnoreCase("all")) {
            updateAll(sender, data);
            return;
        }

        TrackedPlugin tracked = resolve(wanted);

        if (tracked == null) {
            send(sender, Messages.unknownPlugin(wanted));
            return;
        }

        plugin.getScheduler().runAsync(task -> {

            try {

                UpdateCandidate candidate = candidateFor(tracked);

                if (candidate == null) {
                    send(sender, Messages.noUpdate(tracked.displayName()));
                    return;
                }

                // The same gate a switch goes through: a newer build may declare something this
                // server does not have, and staging it anyway is a plugin that will not load.
                if (blocked(sender, tracked, candidate, data)) {
                    return;
                }

                plugin.stage(candidate);

                redraw(sender, screen(data));
                send(sender, Messages.staged(tracked.displayName(), candidate.to()));

            } catch (Exception e) {
                send(sender, Messages.failed(rootMessage(e)));
            }
        });
    }

    @Subcommand("cancel")
    @Description("Drop a staged update")
    @RequiresPermission("command.update")
    public void cancel(CommandSender sender,
                       @Named("plugin") @SuggestWith(Suggestions.Tracked.class) String query) {

        TrackedPlugin tracked = resolve(query);

        if (tracked == null) {
            send(sender, Messages.unknownPlugin(query));
            return;
        }

        String data = context.take(sender);

        if (!tracked.pendingRestart()) {
            send(sender, Messages.nothingStaged(tracked.displayName()));
            return;
        }

        plugin.getScheduler().runAsync(task -> {

            if (!plugin.cancelUpdate(tracked)) {
                send(sender, Messages.failed("Could not delete the staged build for "
                        + tracked.displayName() + ", see the console."));
                return;
            }

            redraw(sender, screen(data));
            send(sender, Messages.cancelled(tracked.displayName()));
        });
    }

    /**
     * Sends an update to the dependency screen when the new build needs something that is missing.
     *
     * @return true if the screen was shown, so the caller should stop
     */
    private boolean blocked(CommandSender sender, TrackedPlugin tracked, UpdateCandidate candidate,
                            String data) {

        if (intent(data) != null) {
            return false;
        }

        DependencyResolver.Resolution resolution = plugin.dependenciesOf(candidate.version());

        if (resolution.missing().isEmpty() && resolution.conflicts().isEmpty()) {
            return false;
        }

        ModrinthProject project = plugin.project(tracked.projectId());

        if (project == null) {
            return false;
        }

        showDependencies(sender, project, candidate.version(), resolution, screen(data));
        return true;
    }

    private void updateAll(CommandSender sender, String data) {

        plugin.getScheduler().runAsync(task -> {

            try {

                plugin.refreshUpdates();
                List<UpdateCandidate> candidates = plugin.updates();

                if (candidates.isEmpty()) {
                    send(sender, Messages.upToDate());
                    return;
                }

                int staged = 0;
                List<Component> failures = new ArrayList<>();

                for (UpdateCandidate candidate : candidates) {

                    try {

                        List<DependencyResolver.Requirement> missing =
                                plugin.missingFor(candidate.version());

                        if (!missing.isEmpty()) {
                            failures.add(Messages.needsDependencies(
                                    candidate.plugin().displayName(), missing.size()));
                            continue;
                        }

                        plugin.stage(candidate);
                        staged++;
                    } catch (Exception e) {
                        // One plugin failing is not a reason to abandon the rest of the queue, and
                        // the reason is worth more after the list than buried above it.
                        failures.add(Messages.stageFailed(candidate.plugin().displayName(),
                                rootMessage(e)));
                    }
                }

                redraw(sender, screen(data));

                if (staged > 0) {
                    send(sender, Messages.stagedAll(staged));
                }

                for (Component failure : failures) {
                    send(sender, failure);
                }

            } catch (Exception e) {
                send(sender, Messages.failed(rootMessage(e)));
            }
        });
    }

    @Subcommand("uninstall")
    @Description("Moves a plugin to the trash")
    @RequiresPermission("command.uninstall")
    public void uninstall(CommandSender sender,
                          @Named("plugin") @SuggestWith(Suggestions.Tracked.class) String query) {

        TrackedPlugin tracked = resolve(query);

        if (tracked == null) {
            send(sender, Messages.unknownPlugin(query));
            return;
        }

        if (plugin.isSelf(tracked)) {
            send(sender, Messages.cannotRemoveSelf(tracked.displayName()));
            return;
        }

        String data = context.take(sender);
        String name = tracked.displayName();

        plugin.getScheduler().runAsync(task -> {

            try {

                // Removing is normally offered with an undo rather than a confirmation. That stops
                // being enough when something else needs it: an undo cannot help a server that
                // already failed to start.
                if (!confirmed(sender, "remove:" + tracked.projectId(), data)) {

                    List<TrackedPlugin> dependents = plugin.dependentsOf(tracked);

                    if (!dependents.isEmpty()) {
                        send(sender, Messages.confirmRemove(tracked, dependents, screen(data)));
                        return;
                    }
                }

                TrashBin.Result result = plugin.uninstall(tracked, sender.getName());

                redraw(sender, screen(data));
                send(sender, Messages.removed(name, result == null || result.deleted(),
                        result == null ? null : result.entry(), screen(data)));

            } catch (Exception e) {
                send(sender, Messages.failed(rootMessage(e)));
            }
        });
    }

    @Subcommand("dependencies")
    @Description("What a plugin declares it needs")
    @RequiresPermission("command.info")
    public void dependencies(CommandSender sender,
                             @Named("plugin") @Single @SuggestWith(Suggestions.Tracked.class) String query,
                             @Switch("install") boolean installMissing) {

        String data = context.take(sender);

        plugin.getScheduler().runAsync(task -> {

            try {

                ModrinthProject project = projectFor(query);

                if (project == null) {
                    send(sender, Messages.unknownPlugin(query));
                    return;
                }

                ModrinthVersion version = plugin.installTarget(project.id());

                if (version == null) {
                    send(sender, Messages.noBuild(project.title()));
                    return;
                }

                DependencyResolver.Resolution resolution = plugin.dependenciesOf(version);

                if (installMissing) {

                    List<CatalogPaper.Pending> pending = pendingFor(resolution.missing());

                    if (pending.isEmpty()) {
                        send(sender, Messages.nothingMissing());
                        return;
                    }

                    plugin.install(pending, sender.getName());
                    send(sender, Messages.installedRequired(pending.size()));

                    resolution = plugin.dependenciesOf(version);
                }

                showDependencies(sender, project, version, resolution, screen(data));

            } catch (Exception e) {
                send(sender, Messages.failed(rootMessage(e)));
            }
        });
    }

    /**
     * Draws the dependency screen.
     */
    private void showDependencies(CommandSender sender, ModrinthProject project,
                                  ModrinthVersion version, DependencyResolver.Resolution resolution,
                                  String from) {

        List<DependencyResolver.Requirement> declared = new ArrayList<>(resolution.required());
        declared.addAll(resolution.optional());

        List<DependencyView> rows = views(declared);
        rows.addAll(conflicts(resolution.conflicts()));

        TrackedPlugin tracked = plugin.getTracking().byProjectId(project.id());

        // Only a build other than the installed one is a switch worth carrying through the screen.
        String switchingTo = tracked != null && version != null
                && !version.id().equals(tracked.versionId()) ? version.id() : null;

        send(sender, Messages.dependencies(project, rows, tracked != null, version != null,
                switchingTo, from));
    }

    /**
     * Rows for projects the author marked as incompatible.
     */
    private List<DependencyView> conflicts(List<String> projectIds) {

        Map<String, ModrinthProject> projects = byId(new LinkedHashSet<>(projectIds));
        List<DependencyView> rows = new ArrayList<>();

        for (String id : projectIds) {

            ModrinthProject project = projects.get(id);

            rows.add(new DependencyView(project != null ? project.title() : id,
                    project != null ? project.slug() : id, null, true,
                    DependencyType.INCOMPATIBLE, false));
        }

        return rows;
    }

    /**
     * Turns resolved requirements into rows.
     */
    private List<DependencyView> views(List<DependencyResolver.Requirement> requirements) {

        Map<String, ModrinthProject> projects = projectsFor(requirements);
        List<DependencyView> views = new ArrayList<>();

        for (DependencyResolver.Requirement requirement : requirements) {

            ModrinthProject project = projects.get(requirement.projectId());
            TrackedPlugin installed = plugin.getTracking().byProjectId(requirement.projectId());

            String version = installed != null ? installed.versionNumber()
                    : requirement.available() == null ? null
                            : requirement.available().versionNumber();

            views.add(new DependencyView(
                    project != null ? project.title() : requirement.projectId(),
                    project != null ? project.slug() : requirement.projectId(),
                    version,
                    requirement.installed(),
                    requirement.type(),
                    requirement.available() != null));
        }

        return views;
    }

    /**
     * What to install for each missing requirement.
     */
    private List<CatalogPaper.Pending> pendingFor(List<DependencyResolver.Requirement> missing) {

        Map<String, ModrinthProject> projects = projectsFor(missing);
        List<CatalogPaper.Pending> pending = new ArrayList<>();

        for (DependencyResolver.Requirement requirement : missing) {

            ModrinthProject project = projects.get(requirement.projectId());

            if (project == null || requirement.available() == null) {
                continue;
            }

            ReleaseChannel follow = requirement.available().versionType() == null
                    ? defaultChannel() : requirement.available().versionType();

            pending.add(new CatalogPaper.Pending(project, requirement.available(), follow, false));
        }

        return pending;
    }

    private Map<String, ModrinthProject> projectsFor(List<DependencyResolver.Requirement> requirements) {

        Set<String> ids = new LinkedHashSet<>();

        for (DependencyResolver.Requirement requirement : requirements) {
            ids.add(requirement.projectId());
        }

        return byId(ids);
    }

    private Map<String, ModrinthProject> byId(Set<String> ids) {

        Map<String, ModrinthProject> projects = new HashMap<>();

        if (ids.isEmpty()) {
            return projects;
        }

        try {
            for (ModrinthProject project : plugin.getModrinth().projects(ids).join()) {
                projects.put(project.id(), project);
            }
        } catch (Exception ignored) {
            // The ids are still enough to name and act on a row.
        }

        return projects;
    }

    /**
     * Which answer to the dependency question a payload carries.
     */
    private static String intent(String data) {

        if (data == null) {
            return null;
        }

        if (data.startsWith(ClickContext.WITH_DEPENDENCIES)) {
            return ClickContext.WITH_DEPENDENCIES;
        }

        return data.startsWith(ClickContext.ALONE) ? ClickContext.ALONE : null;
    }

    @Subcommand("trash")
    @Description("Show plugins you have removed")
    @RequiresPermission("command.trash")
    public void trash(CommandSender sender, @Flag("page") @Default("1") int page) {
        plugin.getScheduler().runAsync(task -> showTrash(sender, Math.max(page, 1)));
    }

    private void showTrash(CommandSender sender, int page) {
        abandonConfirmation(sender);
        send(sender, Messages.trash(plugin.trashed(),
                plugin.getConfiguration().trash.retentionDays, page));
    }

    /**
     * Restores a trashed plugin.
     */
    @Subcommand("trash restore")
    @Description("Put a removed plugin back")
    @RequiresPermission("command.trash")
    public void restore(CommandSender sender,
                        @Named("removal") @SuggestWith(Suggestions.Trashed.class) String query) {

        String data = context.take(sender);

        plugin.getScheduler().runAsync(task -> {

            try {

                TrashEntry entry = resolveTrashed(query);

                if (entry == null) {
                    send(sender, Messages.nothingToRestore());
                    return;
                }

                TrackedPlugin tracked = plugin.restore(entry, sender.getName());

                redraw(sender, screen(data));
                send(sender, Messages.restored(entry.displayName(), tracked != null));

            } catch (Exception e) {
                send(sender, Messages.failed(rootMessage(e)));
            }
        });
    }

    /**
     * Deletes a trash entry permanently (or all of them).
     */
    @Subcommand("trash delete")
    @Description("Delete a removal permanently")
    @RequiresPermission("command.trash")
    public void delete(CommandSender sender,
                       @Named("removal") @SuggestWith(Suggestions.Discardable.class) String query) {

        String data = context.take(sender);

        if ("all".equalsIgnoreCase(ClickContext.strip(query).trim())) {
            emptyTrash(sender, data);
            return;
        }

        plugin.getScheduler().runAsync(task -> {

            try {

                TrashEntry entry = resolveTrashed(query);

                if (entry == null) {
                    send(sender, Messages.nothingToRestore());
                    return;
                }

                plugin.discardTrashed(entry);

                redraw(sender, screen(data));
                send(sender, Messages.discarded(entry.displayName()));

            } catch (Exception e) {
                send(sender, Messages.failed(rootMessage(e)));
            }
        });
    }

    private void emptyTrash(CommandSender sender, String data) {

        plugin.getScheduler().runAsync(task -> {

            try {

                int waiting = plugin.trashed().size();

                if (waiting == 0) {
                    send(sender, Messages.trashAlreadyEmpty());
                    return;
                }

                if (!confirmed(sender, "empty", data)) {
                    send(sender, Messages.confirmEmpty(waiting));
                    return;
                }

                int deleted = plugin.emptyTrash();

                redraw(sender, screen(data));
                send(sender, Messages.emptied(deleted));

            } catch (Exception e) {
                send(sender, Messages.failed(rootMessage(e)));
            }
        });
    }

    /**
     * Finds a trashed plugin by file name and plugin name.
     */
    private TrashEntry resolveTrashed(String query) {

        String wanted = ClickContext.strip(query).trim();
        TrashEntry exact = plugin.trashed(wanted);

        if (exact != null) {
            return exact;
        }

        String lowered = wanted.toLowerCase(Locale.ROOT);

        for (TrashEntry entry : plugin.trashed()) {

            if (matches(entry.name(), lowered)
                    || matches(entry.slug(), lowered)
                    || matches(entry.fileName(), lowered)
                    || matches(entry.projectId(), lowered)) {
                return entry;
            }
        }

        return null;
    }

    @Subcommand("hold")
    @Description("Freeze a plugin at its installed version")
    @RequiresPermission("command.hold")
    public void hold(CommandSender sender,
                     @Named("plugin") @SuggestWith(Suggestions.Holdable.class) String query) {
        setHeld(sender, query, true);
    }

    @Subcommand("unhold")
    @Description("Let a plugin be updated again")
    @RequiresPermission("command.hold")
    public void unhold(CommandSender sender,
                       @Named("plugin") @SuggestWith(Suggestions.Held.class) String query) {
        setHeld(sender, query, false);
    }

    private void setHeld(CommandSender sender, String query, boolean held) {

        TrackedPlugin tracked = resolve(query);

        if (tracked == null) {
            send(sender, Messages.unknownPlugin(query));
            return;
        }

        plugin.setHeld(tracked, held);
        done(sender, context.take(sender), Messages.held(tracked.displayName(), held));
    }

    // --- shared ------------------------------------------------------------------------

    /**
     * Draws the screen the button was on again, and then states the action taken.ì
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param data what the button said it was on, or null
     */
    private void redraw(CommandSender sender, String data) {

        if (data == null) {
            return;
        }

        if (data.equals(ClickContext.LIST)) {
            showList(sender, false);
        } else if (data.equals(ClickContext.TRASH)) {
            showTrash(sender, 1);
        } else if (data.startsWith(ClickContext.DEPENDENCIES)) {
            dependencies(sender, data.substring(ClickContext.DEPENDENCIES.length()), false);
        } else if (data.startsWith(ClickContext.INFO)) {
            showProject(sender, data.substring(ClickContext.INFO.length()));
        } else if (data.startsWith(ClickContext.SETTINGS)) {

            TrackedPlugin tracked = resolve(data.substring(ClickContext.SETTINGS.length()));

            if (tracked != null) {
                showSettings(sender, tracked, null);
            }
        }
    }

    /**
     * Renders one plugin's settings.
     */
    private void showSettings(CommandSender sender, TrackedPlugin tracked, String from) {
        abandonConfirmation(sender);
        send(sender, Messages.settings(tracked, plugin.defaultSoakMinutes(), from));
    }

    private static String key(TrackedPlugin plugin) {
        return plugin.slug() != null ? plugin.slug() : plugin.displayName();
    }

    /**
     * Forgets a removal waiting to be confirmed.
     *
     * <p>Called whenever a screen is drawn, which is what makes Cancel actually cancel: the button
     * navigates away, and without this the confirmation would still be armed, so pressing the same
     * remove button again inside the window would go straight through without asking.</p>
     */
    private void abandonConfirmation(CommandSender sender) {
        confirmations.remove(sender.getName());
    }

    /**
     * Whether to go ahead, or to ask first.
     *
     * @param action identifies precisely what is being confirmed
     * @param data   the payload the command arrived with, or null if it was typed
     * @return true to go ahead, false when the caller should show a confirmation instead
     */
    private boolean confirmed(CommandSender sender, String action, String data) {

        if (data != null) {
            return data.startsWith(ClickContext.CONFIRM);
        }

        Pending pending = confirmations.get(sender.getName());

        if (pending != null && !pending.expired() && pending.action().equals(action)) {
            confirmations.remove(sender.getName());
            return true;
        }

        confirmations.put(sender.getName(), new Pending(action, Instant.now()));
        return false;
    }

    /**
     * The screen a payload points at.
     */
    private static String screen(String data) {

        if (data == null) {
            return null;
        }

        String screen = data;

        for (String marker : new String[]{ClickContext.CONFIRM, ClickContext.WITH_DEPENDENCIES,
                ClickContext.ALONE}) {

            if (screen.startsWith(marker)) {
                screen = screen.substring(marker.length());
                break;
            }
        }

        return screen.isEmpty() ? null : screen;
    }

    /**
     * Renders the plugin list.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param refresh whether to ask Modrinth again first, which is wasted after an action that
     *                already knows what changed
     */
    private void showList(CommandSender sender, boolean refresh) {

        abandonConfirmation(sender);

        if (plugin.getTracking().size() == 0) {
            send(sender, Messages.nothingTracked());
            return;
        }

        if (refresh) {

            try {
                plugin.refreshUpdates();
            } catch (Exception e) {
                send(sender, Messages.unreachable(rootMessage(e)));
            }
        }

        send(sender, Messages.list(plugin.getTracking().all(), plugin.updatesByProject(),
                plugin.ownFileName()));
    }

    /**
     * Builds and sends the project page.
     */
    private void showProject(CommandSender sender, String query) {

        abandonConfirmation(sender);

        try {

            ModrinthProject project = projectFor(query);

            if (project == null) {
                send(sender, Messages.unknownPlugin(query));
                return;
            }

            TrackedPlugin tracked = plugin.getTracking().byProjectId(project.id());

            ReleaseChannel channel = tracked != null ? tracked.channel() : defaultChannel();

            List<ModrinthVersion> compatible = compatibleOrEmpty(project.id());
            ModrinthVersion latest = newestOn(compatible, channel);

            ProjectView.ProjectViewBuilder view = ProjectView.builder()
                    .project(project)
                    .author(author(project.id()))
                    .latest(latest)
                    .installTarget(CatalogPaper.installTarget(compatible))
                    .installed(tracked)
                    .self(plugin.isSelf(tracked))
                    .updateAvailable(isNewer(latest, tracked))
                    .platformLoaders(plugin.platformLoaders());

            for (ProjectView.Requirement requirement : requirements(latest)) {
                view.requirement(requirement);
            }

            for (ProjectView.Requirement optional : optionals(latest)) {
                view.optional(optional);
            }

            send(sender, Messages.project(view.build()));

        } catch (Exception e) {
            send(sender, Messages.unreachable(rootMessage(e)));
        }
    }

    private void runSearch(CommandSender sender, String query, int page) {
        plugin.getScheduler().runAsync(task -> showSearch(sender, query, page));
    }

    /**
     * Renders a page of search results.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     */
    private void showSearch(CommandSender sender, String query, int page) {

        abandonConfirmation(sender);

        try {

            SearchResults results = plugin.search(query, Messages.PAGE,
                    (page - 1) * Messages.PAGE);

            // Text search does not match project ids, so a query that found nothing and could
            // be one is worth one direct lookup before giving up.
            if (results.hits().isEmpty() && !query.contains(" ")) {
                showProject(sender, query);
                return;
            }

            send(sender, Messages.search(query, results, page, trackedProjectIds()));

        } catch (Exception e) {
            send(sender, Messages.unreachable(rootMessage(e)));
        }
    }

    /**
     * The update offered for a plugin checked again if the cache doesn't have it.
     */
    private UpdateCandidate candidateFor(TrackedPlugin tracked) {

        UpdateCandidate candidate = plugin.updatesByProject().get(tracked.projectId());

        if (candidate != null) {
            return candidate;
        }

        plugin.refreshUpdates();
        return plugin.updatesByProject().get(tracked.projectId());
    }

    private List<ProjectView.Requirement> requirements(ModrinthVersion version) {
        return declared(version, DependencyType.REQUIRED);
    }

    private List<ProjectView.Requirement> optionals(ModrinthVersion version) {
        return declared(version, DependencyType.OPTIONAL);
    }

    private List<ProjectView.Requirement> declared(ModrinthVersion version, DependencyType type) {

        if (version == null) {
            return List.of();
        }

        Set<String> ids = new LinkedHashSet<>();

        for (Dependency dependency : version.dependenciesOf(type)) {
            if (dependency.projectId() != null) {
                ids.add(dependency.projectId());
            }
        }

        if (ids.isEmpty()) {
            return List.of();
        }

        List<ProjectView.Requirement> requirements = new ArrayList<>();
        Set<String> installed = trackedProjectIds();

        try {

            for (ModrinthProject required : plugin.getModrinth().projects(ids).join()) {
                requirements.add(new ProjectView.Requirement(required.title(),
                        installed.contains(required.id())));
            }

        } catch (Exception e) {

            for (String id : ids) {
                requirements.add(new ProjectView.Requirement(id, installed.contains(id)));
            }
        }

        return requirements;
    }

    private ModrinthProject firstSearchHit(String query) {

        SearchResults results = plugin.search(query, 1, 0);

        if (results.hits().isEmpty()) {
            return null;
        }

        SearchHit hit = results.hits().get(0);
        return plugin.project(hit.slug() != null ? hit.slug() : hit.projectId());
    }

    /**
     * Who to credit for a project.
     */
    private String author(String projectId) {

        try {
            return TeamMember.credit(plugin.getModrinth().members(projectId).join());
        } catch (Exception e) {
            return null;
        }
    }

    private List<ModrinthVersion> compatibleOrEmpty(String projectId) {

        try {
            return plugin.compatibleVersions(projectId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static ModrinthVersion newestOn(List<ModrinthVersion> versions, ReleaseChannel channel) {

        for (ModrinthVersion version : versions) {
            if (version.versionType() == null || channel.accepts(version.versionType())) {
                return version;
            }
        }

        return null;
    }

    /**
     * Whether a build is older than what is installed.
     */
    private static boolean isOlder(ModrinthVersion version, TrackedPlugin tracked) {

        return tracked.datePublished() != null && version.datePublished() != null
                && version.datePublished().isBefore(tracked.datePublished());
    }

    /**
     * Whether a build is newer than what is installed.
     */
    private static boolean isNewer(ModrinthVersion latest, TrackedPlugin tracked) {

        if (latest == null || tracked == null || tracked.pinnedVersionId() != null) {
            return false;
        }

        if (latest.id().equals(tracked.versionId())) {
            return false;
        }

        return tracked.datePublished() == null
                || latest.datePublished().isAfter(tracked.datePublished());
    }

    private Set<String> trackedProjectIds() {

        Set<String> ids = new HashSet<>();

        for (TrackedPlugin tracked : plugin.getTracking().all()) {
            ids.add(tracked.projectId());
        }

        return ids;
    }

    private ReleaseChannel defaultChannel() {
        return plugin.getConfiguration().tracking.defaults.channel;
    }

    /**
     * A project found by slug or id.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param query what was typed or clicked, payload and all
     * @return the project, or null when nothing matched
     */
    private ModrinthProject projectFor(String query) {

        String wanted = ClickContext.strip(query).trim();
        TrackedPlugin tracked = resolve(wanted);

        ModrinthProject project = plugin.project(tracked != null ? tracked.projectId() : wanted);
        return project != null ? project : firstSearchHit(wanted);
    }

    /**
     * Finds a tracked plugin.
     *
     * <p>An exact match on the display name, slug or project id wins; otherwise the first whose
     * name starts with what was typed, so partial names work.</p>
     */
    private TrackedPlugin resolve(String query) {

        String wanted = ClickContext.strip(query).toLowerCase(Locale.ROOT);
        List<TrackedPlugin> all = plugin.getTracking().all();

        for (TrackedPlugin tracked : all) {
            if (matches(tracked.displayName(), wanted)
                    || matches(tracked.slug(), wanted)
                    || matches(tracked.projectId(), wanted)) {
                return tracked;
            }
        }

        for (TrackedPlugin tracked : all) {
            if (tracked.displayName() != null
                    && tracked.displayName().toLowerCase(Locale.ROOT).startsWith(wanted)) {
                return tracked;
            }
        }

        return null;
    }

    private static boolean matches(String value, String wanted) {
        return value != null && value.toLowerCase(Locale.ROOT).equals(wanted);
    }

    private static String rootMessage(Throwable error) {

        Throwable cause = error;

        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }

        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static void send(CommandSender sender, List<Component> lines) {

        sender.sendMessage(Component.empty());

        for (Component line : lines) {
            sender.sendMessage(shown(sender, line));
        }
    }

    private static void send(CommandSender sender, Component line) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(shown(sender, line));
    }

    /**
     * A screen as this sender can use it.
     *
     * <p>Console, command blocks and RCON cannot click or hover, so a button there is a label with
     * no way to act on it and no way to find out what it would have run.</p>
     */
    private static Component shown(CommandSender sender, Component line) {
        return sender instanceof Player ? line : Messages.typed(line);
    }

    /**
     * @param action what is waiting to be confirmed, identified precisely enough that confirming
     *               one thing can never carry out another
     */
    private record Pending(String action, Instant asked) {

        private boolean expired() {
            return Duration.between(asked, Instant.now()).compareTo(CONFIRM_WINDOW) > 0;
        }

    }


}
