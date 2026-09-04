package top.vulpine.catalog.paper.command;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
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
import top.vulpine.catalog.update.model.UpdateCandidate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code /catalog} command.
 *
 * <p>Anything that can be answered from memory is, and anything that cannot runs off the main
 * thread. The plugin list refreshes its updates on every call rather than showing a cached number
 * with an age next to it: a manager that reports stale counts is a manager nobody trusts.</p>
 */
@Command({"catalog", "ctlg"})
public final class MainCommand {

    /** How long a removal stays confirmable before it has to be asked for again. */
    private static final Duration CONFIRM_WINDOW = Duration.ofSeconds(30);

    /**
     * How many words an argument that carries a flag may be.
     *
     * <p>Lamp wants a flag to be the last parameter and a greedy parameter to be the last
     * parameter, so free-form text and a flag cannot both be greedy. A bounded list is the way out:
     * a size cap makes it non-greedy at registration, while parsing still consumes every remaining
     * word — and the flag is lifted out of the input before that happens, so it is never mistaken
     * for one. The cap is only there to be far past anything anyone would type.</p>
     */
    private static final int MAX_WORDS = 12;

    private final CatalogPaper plugin;

    /** Pending removals, by sender name. Console counts as one sender, which is correct. */
    private final Map<String, Pending> confirmations = new ConcurrentHashMap<>();

    /** Which screen the button that ran this command was on, if it was a button at all. */
    private final ClickContext context;

    public MainCommand(CatalogPaper plugin, ClickContext context) {
        this.plugin = plugin;
        this.context = context;
    }

    @Description("What Catalog is")
    @RequiresPermission("command.about")
    public void about(CommandSender sender) {

        List<String> authors = plugin.getDescription().getAuthors();

        send(sender, Messages.about(plugin.getDescription().getVersion(),
                authors.isEmpty() ? "Vulpine" : String.join(", ", authors)));
    }

    @Subcommand("help")
    @Description("Every command")
    @RequiresPermission("command.about")
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

        // The version argument is last and not single, so it is greedy: a payload can ride on the
        // end of it, and this is the fallback for the rare command the listener never saw.
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

                // Naming a build of something already installed is a request to switch to it,
                // which is the only way back from a release that broke the server.
                if (tracked != null && named == null) {
                    send(sender, Messages.alreadyInstalled(tracked));
                    return;
                }

                if (tracked != null && tracked.isPinned()) {
                    send(sender, Messages.failed(tracked.displayName()
                            + " is held at its current version. Unhold it first."));
                    return;
                }

                List<ModrinthVersion> compatible = plugin.compatibleVersions(project.id());
                ModrinthVersion version = choose(compatible, named);

                // A build named outright, that this server is not declared compatible with. Only
                // reachable from the unfiltered list, which only exists when it is switched on.
                if (version == null && named != null
                        && plugin.getConfiguration().allowIncompatibleInstalls) {
                    version = choose(plugin.allVersions(project.id()), named);
                }

                if (version == null) {
                    send(sender, Messages.failed(named == null
                            ? project.title() + " has no build for this server"
                            : "No build of " + project.title() + " called " + named));
                    return;
                }

                // The channel to follow from now on is the one just chosen. Anything stricter would
                // leave a plugin installed on beta never seeing another update.
                ReleaseChannel follow = version.versionType() == null
                        ? defaultChannel() : version.versionType();

                if (tracked != null) {

                    // Replacing a jar that already works is worth a second press. A fresh install
                    // is not: there is nothing yet to lose.
                    if (!confirmed(sender, "switch:" + tracked.projectId() + ":" + version.id(), data)) {
                        send(sender, Messages.confirmSwitch(tracked, version,
                                isOlder(version, tracked), screen(data)));
                        return;
                    }

                    plugin.setChannel(tracked, follow);
                    plugin.stage(tracked, version);

                    redraw(sender, screen(data));
                    send(sender, Messages.staged(tracked.displayName(), version.versionNumber()));
                    return;
                }

                plugin.install(project, version, follow, sender.getName());

                redraw(sender, screen(data));
                send(sender, Messages.installed(project.title(), version.versionNumber()));

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
                        installed, allowed));

            } catch (Exception e) {
                send(sender, Messages.failed("Could not reach Modrinth: " + rootMessage(e)));
            }
        });
    }

    /**
     * The newest build of each channel exactly, which is the choice the picker offers.
     *
     * <p>Matched on the channel a build was published as, not on which channels would accept it.
     * A subscriber to beta also accepts releases, but "the newest beta" and "the newest build a
     * beta subscriber would take" are different things, and only the first is a choice.</p>
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
     * Finds the build someone named, by version id or by the number they can actually see.
     *
     * @param wanted the id or version number, or null to take what installing would default to
     */
    private static ModrinthVersion choose(List<ModrinthVersion> compatible, String wanted) {

        if (wanted == null) {
            return installTarget(compatible);
        }

        for (ModrinthVersion version : compatible) {
            if (wanted.equals(version.id()) || wanted.equalsIgnoreCase(version.versionNumber())) {
                return version;
            }
        }

        return null;
    }

    /**
     * Rereads config.yml.
     *
     * <p>Only the settings are reloaded, never the tracking state: that is the record of what
     * Catalog is responsible for, and rebuilding it from disk while the server runs would risk
     * acting on a version of the truth nobody asked for.</p>
     */
    @Subcommand("reload")
    @Description("Reload the configuration")
    @RequiresPermission("command.reload")
    public void reload(CommandSender sender) {

        send(sender, plugin.loadConfiguration()
                ? Messages.reloaded()
                : Messages.failed("Could not read config.yml, see the console. "
                        + "The settings already loaded are still in use."));
    }

    @Subcommand("settings")
    @Description("What a plugin does on its own")
    @RequiresPermission("command.settings")
    public void settings(CommandSender sender,
                         @Named("plugin") @SuggestWith(Suggestions.Tracked.class) String query) {

        TrackedPlugin tracked = resolve(query);

        if (tracked == null) {
            send(sender, Messages.unknownPlugin(query));
            return;
        }

        showSettings(sender, tracked);
    }

    @Subcommand("auto")
    @Description("Whether a plugin updates itself")
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
        afterSettingChanged(sender, tracked, Messages.autoSet(tracked.displayName(), state.on()));
    }

    @Subcommand("soak")
    @Description("How long a build must be public before this plugin takes it")
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
            send(sender, Messages.failed("Say a number of minutes, something like 30m or 2h, "
                    + "or default to follow the config"));
            return;
        }

        plugin.setSoak(tracked, minutes);
        afterSettingChanged(sender, tracked, Messages.soakSet(tracked.displayName(), minutes,
                plugin.defaultSoakMinutes()));
    }

    /**
     * Reads a soak window the way someone would write one.
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
     * Settings are only ever offered from the settings screen, and none of these commands can carry
     * a payload — each has an argument after the plugin name, so the client refuses to parse
     * anything trailing. Falling back to that screen is the same reasoning install uses.
     */
    private void afterSettingChanged(CommandSender sender, TrackedPlugin tracked, Component outcome) {

        String data = context.take(sender);
        String screen = data != null ? data : ClickContext.SETTINGS + key(tracked);

        plugin.getScheduler().runAsync(task -> {
            redraw(sender, screen);
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
        afterSettingChanged(sender, tracked, Messages.channelSet(tracked.displayName(), channel));
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

                plugin.stage(candidate);

                redraw(sender, data);
                send(sender, Messages.staged(tracked.displayName(), candidate.to()));

            } catch (Exception e) {
                send(sender, Messages.failed(rootMessage(e)));
            }
        });
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
                        plugin.stage(candidate);
                        staged++;
                    } catch (Exception e) {
                        // One plugin failing is not a reason to abandon the rest of the queue, and
                        // the reason is worth more after the list than buried above it.
                        failures.add(Messages.failed(candidate.plugin().displayName()
                                + ": " + rootMessage(e)));
                    }
                }

                redraw(sender, data);

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

    /**
     * Asks first, then removes.
     *
     * <p>The same command does both: the confirmation button runs it again, and a second call
     * within the window goes through. One subcommand instead of two, and nothing to type that only
     * makes sense as the second half of something else.</p>
     */
    @Subcommand("uninstall")
    @Description("Move a plugin to the trash")
    @RequiresPermission("command.uninstall")
    public void uninstall(CommandSender sender,
                          @Named("plugin") @SuggestWith(Suggestions.Tracked.class) String query) {

        TrackedPlugin tracked = resolve(query);

        if (tracked == null) {
            send(sender, Messages.unknownPlugin(query));
            return;
        }

        // Blocked here as well as hidden from the buttons: the command can still be typed.
        if (plugin.isSelf(tracked)) {
            send(sender, Messages.cannotRemoveSelf(tracked.displayName()));
            return;
        }

        String data = context.take(sender);

        if (!confirmed(sender, "remove:" + tracked.projectId(), data)) {

            // The button carries the payload back, so confirming lands on the screen this started
            // from rather than on whatever the confirmation itself replaced.
            send(sender, Messages.confirmRemove(tracked, screen(data)));
            return;
        }

        plugin.getScheduler().runAsync(task -> {

            try {

                boolean deleted = plugin.uninstall(tracked, sender.getName());

                redraw(sender, screen(data));
                send(sender, Messages.removed(tracked.displayName(), deleted));

            } catch (Exception e) {
                send(sender, Messages.failed(rootMessage(e)));
            }
        });
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
        String data = context.take(sender);

        plugin.getScheduler().runAsync(task -> {
            redraw(sender, data);
            send(sender, Messages.held(tracked.displayName(), held));
        });
    }

    // --- shared work ------------------------------------------------------------------------

    /**
     * Draws the screen the button was on again, and only then says what happened.
     *
     * <p>A confirmation on its own leaves the screen above it lying: the button that was just
     * pressed is still offering to do the thing it already did. Redrawing puts the truth at the
     * bottom of the chat, where the eye already is, and the outcome lands under it.</p>
     *
     * <p>A typed command carries no payload and gets only the outcome, which is right — there is no
     * screen to put back, and nobody who types a one-line command wants a page in return.</p>
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
        } else if (data.startsWith(ClickContext.INFO)) {
            showProject(sender, data.substring(ClickContext.INFO.length()));
        } else if (data.startsWith(ClickContext.SETTINGS)) {

            TrackedPlugin tracked = resolve(data.substring(ClickContext.SETTINGS.length()));

            if (tracked != null) {
                showSettings(sender, tracked);
            }
        }
    }

    /**
     * Renders one plugin's settings. Answers from memory, so it does not block.
     */
    private void showSettings(CommandSender sender, TrackedPlugin tracked) {
        abandonConfirmation(sender);
        send(sender, Messages.settings(tracked, plugin.defaultSoakMinutes()));
    }

    private static String key(TrackedPlugin plugin) {
        return plugin.slug() != null ? plugin.slug() : plugin.displayName();
    }

    /**
     * Forgets a removal that was waiting to be confirmed.
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
     * <p>A click says outright which it is, because the confirmation button carries a payload the
     * button that asked does not. Pressing the same remove or version button twice therefore asks
     * twice, instead of quietly carrying the action out — which is what happened while confirming
     * meant nothing more than running the same command again.</p>
     *
     * <p>A typed command has no payload to carry, so there it still means exactly that: the same
     * command a second time inside the window. Console cannot click, and needs a way through.</p>
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
     * The screen a payload points at, with any confirmation marker peeled off.
     */
    private static String screen(String data) {

        if (data == null) {
            return null;
        }

        String screen = data.startsWith(ClickContext.CONFIRM)
                ? data.substring(ClickContext.CONFIRM.length()) : data;

        return screen.isEmpty() ? null : screen;
    }

    /**
     * Renders the plugin list. Blocks, so it must be called off the main thread.
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
                send(sender, Messages.failed("Could not reach Modrinth: " + rootMessage(e)));
            }
        }

        send(sender, Messages.list(plugin.getTracking().all(), plugin.updatesByProject(),
                plugin.ownFileName()));
    }

    /**
     * Builds and sends the project page.
     *
     * <p>Runs on an async thread and asks Modrinth up to three times: the project, its newest
     * compatible build, and the names of what that build requires. Everything is gathered before
     * anything is drawn, so a half-answered page is never shown.</p>
     */
    private void showProject(CommandSender sender, String query) {

        abandonConfirmation(sender);

        try {

            TrackedPlugin tracked = resolve(query);
            ModrinthProject project = plugin.project(tracked != null ? tracked.projectId() : query);

            // A name rather than a slug reaches Modrinth's search but not its project endpoint,
            // so the closest match stands in for the exact one.
            if (project == null) {
                project = firstSearchHit(query);
            }

            if (project == null) {
                send(sender, Messages.unknownPlugin(query));
                return;
            }

            if (tracked == null) {
                tracked = plugin.getTracking().byProjectId(project.id());
            }

            ReleaseChannel channel = tracked != null ? tracked.channel() : defaultChannel();

            // One request answers all three questions: what the followed channel offers, what
            // installing would fetch, and how many builds a picker would have to show.
            List<ModrinthVersion> compatible = compatibleOrEmpty(project.id());
            ModrinthVersion latest = newestOn(compatible, channel);

            ProjectView.ProjectViewBuilder view = ProjectView.builder()
                    .project(project)
                    .author(author(project.id()))
                    .latest(latest)
                    .installTarget(installTarget(compatible))
                    .installed(tracked)
                    .self(plugin.isSelf(tracked))
                    .updateAvailable(isNewer(latest, tracked))
                    .platformLoaders(plugin.platformLoaders());

            for (ProjectView.Requirement requirement : requirements(latest)) {
                view.requirement(requirement);
            }

            send(sender, Messages.project(view.build()));

        } catch (Exception e) {
            send(sender, Messages.failed("Could not reach Modrinth: " + rootMessage(e)));
        }
    }

    private void runSearch(CommandSender sender, String query, int page) {
        plugin.getScheduler().runAsync(task -> showSearch(sender, query, page));
    }

    /**
     * Renders a page of search results. Blocks, so it must be called off the main thread.
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
            send(sender, Messages.failed("Could not reach Modrinth: " + rootMessage(e)));
        }
    }

    /**
     * The update on offer for a plugin, checking again if the last answer did not mention it.
     */
    private UpdateCandidate candidateFor(TrackedPlugin tracked) {

        UpdateCandidate candidate = plugin.updatesByProject().get(tracked.projectId());

        if (candidate != null) {
            return candidate;
        }

        plugin.refreshUpdates();
        return plugin.updatesByProject().get(tracked.projectId());
    }

    /**
     * Turns the required dependencies of a build into names, in one request.
     */
    private List<ProjectView.Requirement> requirements(ModrinthVersion version) {

        if (version == null) {
            return List.of();
        }

        Set<String> ids = new LinkedHashSet<>();

        for (Dependency dependency : version.dependenciesOf(DependencyType.REQUIRED)) {
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

            // Names are a nicety; which ones are missing is the part that matters.
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
     *
     * <p>A missing answer is not worth failing a page over, so it simply goes unsaid.</p>
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
     * What installing without naming a version should fetch.
     *
     * <p>The newest stable build, because that is what almost everyone wants and nobody should be
     * handed an alpha by accident. When a project has never published one for this server the
     * newest of whatever exists is offered instead, and the button says so — refusing outright and
     * calling it "no build for this server" is a lie about a project that plainly has one.</p>
     */
    private static ModrinthVersion installTarget(List<ModrinthVersion> versions) {

        ModrinthVersion stable = newestOn(versions, ReleaseChannel.RELEASE);
        return stable != null ? stable : versions.isEmpty() ? null : versions.get(0);
    }

    /**
     * Whether a build is genuinely newer than what is installed.
     *
     * <p>A different id is not enough. Narrowing to one game version can make the newest compatible
     * build an older one, and offering that is how an operator gets talked into a downgrade.</p>
     */
    /**
     * Whether a build was published before the one installed, which makes choosing it a rollback.
     *
     * <p>By publish date, never by reading the version numbers: those are display strings their
     * authors chose, and deciding a rollback from one is how a manager talks somebody into the
     * opposite of what they asked for.</p>
     */
    private static boolean isOlder(ModrinthVersion version, TrackedPlugin tracked) {

        return tracked.datePublished() != null && version.datePublished() != null
                && version.datePublished().isBefore(tracked.datePublished());
    }

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
     * Finds a tracked plugin the way a person would name it.
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

    /**
     * Sends a block of lines with an empty one above it, so consecutive messages do not read as one
     * wall of text in a busy chat.
     */
    private static void send(CommandSender sender, List<Component> lines) {

        sender.sendMessage(Component.empty());

        for (Component line : lines) {
            sender.sendMessage(line);
        }
    }

    private static void send(CommandSender sender, Component line) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(line);
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
