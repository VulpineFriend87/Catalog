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

    public MainCommand(CatalogPaper plugin) {
        this.plugin = plugin;
    }

    @Description("What Catalog is")
    @RequiresPermission("command.about")
    public void about(CommandSender sender) {

        List<String> authors = plugin.getDescription().getAuthors();

        send(sender, Messages.about(plugin.getDescription().getVersion(),
                authors.isEmpty() ? "Vulpine" : String.join(", ", authors)));
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
                        @Optional @Named("channel") ReleaseChannel channel) {

        ReleaseChannel wanted = channel == null ? defaultChannel() : channel;

        plugin.getScheduler().runAsync(task -> {

            try {

                ModrinthProject project = plugin.project(query);

                if (project == null) {
                    send(sender, Messages.unknownPlugin(query));
                    return;
                }

                if (plugin.getTracking().byProjectId(project.id()) != null) {
                    send(sender, Messages.failed(project.title() + " is already installed"));
                    return;
                }

                ModrinthVersion version = plugin.newestCompatible(project.id(), wanted);

                if (version == null) {
                    send(sender, Messages.failed(project.title() + " has no "
                            + wanted.apiName() + " build for this server"));
                    return;
                }

                plugin.install(project, version, wanted, sender.getName());

                showProject(sender, project.slug());
                send(sender, Messages.installed(project.title(), version.versionNumber()));

            } catch (Exception e) {
                send(sender, Messages.failed(rootMessage(e)));
            }
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
        redraw(sender, tracked, Messages.channelSet(tracked.displayName(), channel));
    }

    /**
     * @param fromInfo set by the button on the project page, so the answer comes back as that page
     *                 rather than as the list
     */
    @Subcommand("update")
    @Description("Download an update and stage it for the next restart")
    @RequiresPermission("command.update")
    public void update(CommandSender sender,
                       @Named("plugin") @SuggestWith(Suggestions.TrackedOrAll.class)
                       @Sized(max = MAX_WORDS) List<String> query,
                       @Switch("info") boolean fromInfo) {

        String wanted = String.join(" ", query);

        if (wanted.equalsIgnoreCase("all")) {
            updateAll(sender);
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

                if (fromInfo) {
                    showProject(sender, key(tracked));
                } else {
                    showList(sender, false);
                }

                send(sender, Messages.staged(tracked.displayName(), candidate.to()));

            } catch (Exception e) {
                send(sender, Messages.failed(rootMessage(e)));
            }
        });
    }

    private void updateAll(CommandSender sender) {

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

                showList(sender, false);

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

        Pending pending = confirmations.get(sender.getName());

        if (pending == null || pending.expired() || !pending.projectId.equals(tracked.projectId())) {
            confirmations.put(sender.getName(), new Pending(tracked.projectId(), Instant.now()));
            send(sender, Messages.confirmRemove(tracked));
            return;
        }

        confirmations.remove(sender.getName());

        plugin.getScheduler().runAsync(task -> {

            try {

                boolean deleted = plugin.uninstall(tracked, sender.getName());

                showList(sender, true);
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
        redraw(sender, tracked, Messages.held(tracked.displayName(), held));
    }

    // --- shared work ------------------------------------------------------------------------

    /**
     * Redraws the page an action was taken from, and only then says what happened.
     *
     * <p>A confirmation on its own leaves the message above it lying: the button that was just
     * pressed is still offering to do the thing it already did. Sending the page again puts the
     * truth at the bottom of the chat, where the eye already is, and the outcome line lands under
     * it so nothing is missed.</p>
     */
    private void redraw(CommandSender sender, TrackedPlugin tracked, Component feedback) {

        plugin.getScheduler().runAsync(task -> {
            showProject(sender, key(tracked));
            send(sender, feedback);
        });
    }

    /**
     * Renders the plugin list. Blocks, so it must be called off the main thread.
     *
     * @param refresh whether to ask Modrinth again first, which is wasted after an action that
     *                already knows what changed
     */
    private void showList(CommandSender sender, boolean refresh) {

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

        send(sender, Messages.list(plugin.getTracking().all(), plugin.updatesByProject()));
    }

    /**
     * Builds and sends the project page.
     *
     * <p>Runs on an async thread and asks Modrinth up to three times: the project, its newest
     * compatible build, and the names of what that build requires. Everything is gathered before
     * anything is drawn, so a half-answered page is never shown.</p>
     */
    private void showProject(CommandSender sender, String query) {

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
            ModrinthVersion latest = newestOrNull(project.id(), channel);

            ProjectView.ProjectViewBuilder view = ProjectView.builder()
                    .project(project)
                    .author(author(project.id()))
                    .latest(latest)
                    .installed(tracked)
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

        plugin.getScheduler().runAsync(task -> {

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
        });
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

    private ModrinthVersion newestOrNull(String projectId, ReleaseChannel channel) {

        try {
            return plugin.newestCompatible(projectId, channel);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether a build is genuinely newer than what is installed.
     *
     * <p>A different id is not enough. Narrowing to one game version can make the newest compatible
     * build an older one, and offering that is how an operator gets talked into a downgrade.</p>
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
     * Finds a tracked plugin the way a person would name it.
     *
     * <p>An exact match on the display name, slug or project id wins; otherwise the first whose
     * name starts with what was typed, so partial names work.</p>
     */
    private TrackedPlugin resolve(String query) {

        String wanted = query.toLowerCase(Locale.ROOT);
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

    private static String key(TrackedPlugin plugin) {
        return plugin.slug() != null ? plugin.slug() : plugin.displayName();
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

    private record Pending(String projectId, Instant asked) {

        private boolean expired() {
            return Duration.between(asked, Instant.now()).compareTo(CONFIRM_WINDOW) > 0;
        }

    }

}
