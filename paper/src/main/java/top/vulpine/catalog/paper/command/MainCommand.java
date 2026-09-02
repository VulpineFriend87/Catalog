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
import java.util.Arrays;
import java.util.Collection;
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

    /**
     * The last screen Catalog drew for each sender.
     *
     * <p>An action can be taken from the list, from a project page or from a search result, and the
     * one it came from is left offering to do what has already been done. This is what lets any
     * command put the right screen back — and, just as importantly, leave it alone when it is still
     * accurate: see {@link #redraw(CommandSender, String...)}.</p>
     *
     * <p>Not a command argument, because it cannot be hidden if it is: Lamp always suggests a
     * flag's name, and the Bukkit integration publishes every command node to Brigadier without
     * checking whether it is secret.</p>
     */
    private final Map<String, View> views = new ConcurrentHashMap<>();

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

                redraw(sender, project.id());
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

        plugin.getScheduler().runAsync(task -> {
            redraw(sender, tracked.projectId());
            send(sender, Messages.channelSet(tracked.displayName(), channel));
        });
    }

    @Subcommand("update")
    @Description("Download an update and stage it for the next restart")
    @RequiresPermission("command.update")
    public void update(CommandSender sender,
                       @Named("plugin") @SuggestWith(Suggestions.Updatable.class)
                       @Sized(max = MAX_WORDS) List<String> query) {

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

                redraw(sender, tracked.projectId());
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
                List<String> changed = new ArrayList<>();
                List<Component> failures = new ArrayList<>();

                for (UpdateCandidate candidate : candidates) {

                    try {
                        plugin.stage(candidate);
                        changed.add(candidate.plugin().projectId());
                        staged++;
                    } catch (Exception e) {
                        // One plugin failing is not a reason to abandon the rest of the queue, and
                        // the reason is worth more after the list than buried above it.
                        failures.add(Messages.failed(candidate.plugin().displayName()
                                + ": " + rootMessage(e)));
                    }
                }

                redraw(sender, changed.toArray(new String[0]));

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

                redraw(sender, tracked.projectId());
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

        plugin.getScheduler().runAsync(task -> {
            redraw(sender, tracked.projectId());
            send(sender, Messages.held(tracked.displayName(), held));
        });
    }

    // --- shared work ------------------------------------------------------------------------

    /**
     * Draws the last screen again when what just changed is on it, and only then says what happened.
     *
     * <p>A confirmation on its own leaves the screen above it lying: the button that was just
     * pressed is still offering to do the thing it already did. Redrawing puts the truth at the
     * bottom of the chat, where the eye already is, and the outcome lands under it.</p>
     *
     * <p>The test is whether the screen is <em>wrong</em>, not whether the action came from it.
     * Reading someone's page for one plugin and then removing a different one leaves that page
     * perfectly accurate, and redrawing it would only be a page they did not ask for.</p>
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     */
    private void redraw(CommandSender sender, String... projectIds) {

        View view = views.get(sender.getName());

        if (view == null || !view.staledBy(Arrays.asList(projectIds))) {
            return;
        }

        switch (view.kind()) {
            case LIST -> showList(sender, false);
            case INFO -> showProject(sender, view.key());
            case SEARCH -> showSearch(sender, view.key(), view.page());
        }
    }

    private void remember(CommandSender sender, View view) {
        views.put(sender.getName(), view);
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

        remember(sender, View.list());
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

            remember(sender, View.info(project.slug(), project.id()));
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

        try {

            SearchResults results = plugin.search(query, Messages.PAGE,
                    (page - 1) * Messages.PAGE);

            // Text search does not match project ids, so a query that found nothing and could
            // be one is worth one direct lookup before giving up.
            if (results.hits().isEmpty() && !query.contains(" ")) {
                showProject(sender, query);
                return;
            }

            Set<String> shown = new HashSet<>();

            for (SearchHit hit : results.hits()) {
                shown.add(hit.projectId());
            }

            remember(sender, View.search(query, page, shown));
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

    /**
     * A screen Catalog can draw again, and the projects it has something to say about.
     *
     * @param key      the project slug for a page, the query for a search, unused for the list
     * @param subjects the projects this screen shows, empty for the list, which shows them all
     */
    private record View(Kind kind, String key, int page, Set<String> subjects) {

        private enum Kind {
            LIST, INFO, SEARCH
        }

        private static View list() {
            return new View(Kind.LIST, null, 0, Set.of());
        }

        private static View info(String slug, String projectId) {
            return new View(Kind.INFO, slug, 0, Set.of(projectId));
        }

        private static View search(String query, int page, Set<String> shown) {
            return new View(Kind.SEARCH, query, page, shown);
        }

        /**
         * Whether a change to any of these projects makes this screen wrong.
         *
         * <p>The list carries every managed plugin, so anything at all can stale it. A page or a
         * set of results only goes wrong when it is about the thing that changed.</p>
         */
        private boolean staledBy(Collection<String> projectIds) {

            if (kind == Kind.LIST) {
                return true;
            }

            for (String projectId : projectIds) {
                if (subjects.contains(projectId)) {
                    return true;
                }
            }

            return false;
        }

    }

}
