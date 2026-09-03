package top.vulpine.catalog.paper.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import top.vulpine.catalog.modrinth.model.ModrinthProject;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.modrinth.model.SearchHit;
import top.vulpine.catalog.modrinth.model.SearchResults;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.update.model.UpdateCandidate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every line Catalog says.
 *
 * <p>Built as components, never as MiniMessage strings. Titles and descriptions come from strangers
 * on the internet, and a description containing an apostrophe is enough to close a tag argument
 * early and spill the markup onto the screen. With components there is no string for anyone's text
 * to escape from.</p>
 *
 * <p>Flat by design: no rails, no rules, no boxes. Chat is narrow and the background is whatever the
 * player happens to be looking at, so structure comes from colour and indentation instead of from
 * characters that eat width. Grey is the darkest colour used; dark grey is unreadable against half
 * the game.</p>
 *
 * <p>Everything clickable says what it will do and then shows the command it runs, so nothing is a
 * mystery button. Anything true but rarely wanted — ids, versions, dates, the licence — lives in a
 * hover rather than on the page.</p>
 */
public final class Messages {

    /** How many search results one page shows. */
    public static final int PAGE = 9;

    /** Catalog's colour. Everything the plugin itself owns is this violet. */
    private static final TextColor BRAND = TextColor.color(0xC08CFF);

    /** Something is waiting to be applied. Warm, so it reads apart from the brand. */
    private static final TextColor PENDING = TextColor.color(0xF2C46B);

    /** Destructive. */
    private static final TextColor DANGER = TextColor.color(0xFF7B72);

    /** Done. */
    private static final TextColor DONE = TextColor.color(0x7BE38B);

    private static final NamedTextColor TEXT = NamedTextColor.WHITE;
    private static final NamedTextColor MUTED = NamedTextColor.GRAY;

    private static final String INDENT = "  ";

    private Messages() {
    }

    // --- /catalog ---------------------------------------------------------------------------

    /**
     * The plugin's own card, and the only place the whole command surface is written down.
     *
     * <p>Says nothing about the server's plugins — that is what {@code /catalog list} is for.</p>
     */
    public static List<Component> about(String version, String author) {

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text("Catalog", BRAND).decorate(TextDecoration.BOLD))
                .append(Component.text(" " + version, MUTED))
                .build());

        out.add(line()
                .append(Component.text(INDENT + "by ", MUTED))
                .append(Component.text(author, TEXT))
                .build());

        out.add(Component.empty());

        out.add(entry("list", "", "Managed plugins"));
        out.add(entry("info", "<plugin>", "Full details, installed or not"));
        out.add(entry("search", "<query>", "Find plugins on Modrinth"));
        out.add(entry("install", "<slug> [version]", "Add a plugin"));
        out.add(entry("versions", "<plugin>", "Newest build of each channel"));
        out.add(entry("update", "<plugin|all>", "Download and stage updates"));
        out.add(entry("uninstall", "<plugin>", "Move a plugin to the trash"));
        out.add(entry("channel", "<plugin> <channel>", "Which builds to follow"));
        out.add(entry("hold", "<plugin>", "Freeze a plugin at its version"));
        out.add(entry("reload", "", "Reload the configuration"));

        return out;
    }

    private static Component entry(String name, String arguments, String description) {

        String command = "/catalog " + name + (arguments.isEmpty() ? "" : " ");

        TextComponent.Builder row = line()
                .append(Component.text(INDENT))
                .append(Component.text(name, BRAND));

        if (!arguments.isEmpty()) {
            row.append(Component.text(" " + arguments, MUTED).decorate(TextDecoration.ITALIC));
        }

        return row.append(Component.text("  " + description, MUTED))
                .clickEvent(arguments.isEmpty()
                        ? ClickEvent.runCommand(command)
                        : ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(explain(description, command)))
                .build();
    }

    // --- /catalog list ----------------------------------------------------------------------

    public static List<Component> list(List<TrackedPlugin> plugins, Map<String, UpdateCandidate> updates,
                                       String self) {

        List<TrackedPlugin> ordered = new ArrayList<>(plugins);

        // Every plugin is here; the order only stops the ones asking for a decision being buried.
        ordered.sort(Comparator
                .comparing((TrackedPlugin plugin) -> !updates.containsKey(plugin.projectId()))
                .thenComparing(plugin -> plugin.displayName().toLowerCase(Locale.ROOT)));

        List<Component> out = new ArrayList<>();

        TextComponent.Builder header = line()
                .append(Component.text("Catalog", BRAND).decorate(TextDecoration.BOLD))
                .append(Component.text("  " + plugins.size(), TEXT))
                .append(Component.text(" plugins", MUTED));

        if (!updates.isEmpty()) {
            header.append(Component.text(" · ", MUTED))
                    .append(Component.text(updates.size(), BRAND))
                    .append(Component.text(" to update", MUTED));
        }

        out.add(header.build());
        out.add(Component.empty());

        for (TrackedPlugin plugin : ordered) {
            out.add(row(plugin, updates.get(plugin.projectId()), plugin.fileName().equals(self)));
        }

        out.add(Component.empty());

        TextComponent.Builder footer = line().append(Component.text(INDENT));

        if (!updates.isEmpty()) {
            footer.append(button("Update all", from("/catalog update all", ClickContext.LIST),
                    BRAND, "Stage every update")).append(Component.space());
        }

        footer.append(button("Search", "/catalog search ", MUTED, "Search Modrinth"));

        out.add(footer.build());

        return out;
    }

    private static Component row(TrackedPlugin plugin, UpdateCandidate update, boolean self) {

        TextComponent.Builder row = line()
                .append(Component.text(INDENT))
                .append(name(plugin));

        if (update != null && !plugin.awaitingRestart()) {
            row.append(Component.space()).append(icon("↑", BRAND,
                    from("/catalog update " + key(plugin), ClickContext.LIST),
                    "Stage " + update.to() + " for the next restart"));
        }

        // No remove button on Catalog's own row: pressing it would delete the thing holding the
        // button, and nothing in game could put it back.
        if (!self) {
            row.append(Component.space()).append(icon("×", DANGER,
                    from("/catalog uninstall " + key(plugin), ClickContext.LIST),
                    "Move " + plugin.displayName() + " to the trash"));
        }

        // One word for both, because the only fact that matters here is that a restart is owed.
        // Which of the two it is belongs in the hover, where it is asked for rather than imposed.
        if (plugin.awaitingRestart()) {
            row.append(Component.text("  restart", PENDING));
        } else if (plugin.isPinned()) {
            row.append(Component.text("  held", MUTED));
        }

        return row.build();
    }

    private static Component name(TrackedPlugin plugin) {

        Component hover = Component.text(plugin.displayName(), TEXT)
                .append(Component.newline())
                .append(Component.text(plugin.versionNumber() == null ? "unknown version"
                        : plugin.versionNumber(), MUTED))
                .append(Component.text("  " + plugin.channel().apiName(), MUTED))
                .append(Component.newline())
                .append(Component.text(String.valueOf(plugin.fileName()), MUTED))
                .append(waiting(plugin))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Open the plugin page", TEXT))
                .append(Component.newline())
                .append(Component.text("/catalog info " + key(plugin), MUTED));

        return Component.text(plugin.displayName(), TEXT)
                .clickEvent(ClickEvent.runCommand("/catalog info " + key(plugin)))
                .hoverEvent(HoverEvent.showText(hover));
    }

    /**
     * Which kind of restart a plugin is waiting for, if any.
     *
     * <p>Both mean "not in effect yet" and neither is worth a word on the row itself, but they are
     * not the same event and the difference decides what to do if a restart does not fix it.</p>
     */
    private static Component waiting(TrackedPlugin plugin) {

        if (plugin.pendingRestart()) {
            return Component.newline()
                    .append(Component.text("update staged, applies on restart", PENDING));
        }

        if (plugin.pendingLoad()) {
            return Component.newline()
                    .append(Component.text("installed, loads on restart", PENDING));
        }

        return Component.empty();
    }

    // --- /catalog info ----------------------------------------------------------------------

    /**
     * One Modrinth project, installed or not. This is where every action on a plugin lives, and
     * where a search result lands.
     */
    public static List<Component> project(ProjectView view) {

        ModrinthProject project = view.project();
        TrackedPlugin installed = view.installed();

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text(project.title(), BRAND).decorate(TextDecoration.BOLD))
                .append(status(view))
                .hoverEvent(HoverEvent.showText(identity(view)))
                .build());

        if (project.description() != null && !project.description().isBlank()) {
            out.add(Component.text(INDENT + project.description(), TEXT));
        }

        out.add(Component.empty());

        out.add(line()
                .append(Component.text(INDENT))
                .append(author(view))
                .append(Component.text(compact(project.downloads()), BRAND))
                .append(Component.text(" downloads", MUTED))
                .append(Component.text(" · ", MUTED))
                .append(Component.text(compact(project.followers()), BRAND))
                .append(Component.text(" followers", MUTED))
                .build());

        out.add(field("Loaders", loaders(view)));

        if (project.categories() != null && !project.categories().isEmpty()) {
            out.add(field("Tags", Component.text(String.join(", ", project.categories()), MUTED)));
        }

        if (!view.requirements().isEmpty()) {
            out.add(field("Needs", requirements(view)));
        }

        out.add(Component.empty());
        out.add(actions(view, installed));

        return out;
    }

    private static Component status(ProjectView view) {

        if (view.installed() == null) {
            return Component.text("  not installed", MUTED);
        }

        if (view.installed().pendingRestart()) {
            return Component.text("  update staged", PENDING);
        }

        if (view.installed().pendingLoad()) {
            return Component.text("  loads on restart", PENDING);
        }

        if (view.updateAvailable()) {
            return Component.text("  update available", BRAND);
        }

        if (view.installed().isPinned()) {
            return Component.text("  held", MUTED);
        }

        return Component.text("  installed", DONE);
    }

    /**
     * Everything the page deliberately leaves out: ids, the licence, versions and dates.
     */
    private static Component identity(ProjectView view) {

        ModrinthProject project = view.project();

        TextComponent.Builder hover = Component.text()
                .append(Component.text(project.title(), TEXT))
                .append(Component.newline())
                .append(Component.text(project.slug() + "  " + project.id(), MUTED));

        if (project.license() != null && project.license().id() != null) {
            hover.append(Component.newline())
                    .append(Component.text("licence  ", MUTED))
                    .append(Component.text(project.license().name() == null
                            ? project.license().id() : project.license().name(), TEXT));
        }

        if (view.installed() != null) {

            TrackedPlugin installed = view.installed();

            hover.append(Component.newline())
                    .append(Component.newline())
                    .append(Component.text("installed  ", MUTED))
                    .append(Component.text(String.valueOf(installed.versionNumber()), TEXT))
                    .append(Component.newline())
                    .append(Component.text("channel  ", MUTED))
                    .append(Component.text(installed.channel().apiName(), TEXT))
                    .append(Component.newline())
                    .append(Component.text("file  ", MUTED))
                    .append(Component.text(String.valueOf(installed.fileName()), TEXT));

            if (installed.isPinned()) {
                hover.append(Component.newline())
                        .append(Component.text("held at this version", PENDING));
            }
        }

        ModrinthVersion newest = offered(view);

        if (newest != null) {
            // Not the project's newest build: the newest one that runs here, on the channel being
            // followed. Saying "latest" invited exactly the wrong reading.
            hover.append(Component.newline())
                    .append(Component.newline())
                    .append(Component.text("newest here  ", MUTED))
                    .append(Component.text(newest.versionNumber(), TEXT))
                    .append(Component.text(newest.versionType() == null ? ""
                            : "  " + newest.versionType().apiName(), MUTED))
                    .append(Component.newline())
                    .append(Component.text("published  ", MUTED))
                    .append(Component.text(ago(newest.datePublished()), TEXT));
        }

        return hover.build();
    }

    /**
     * The build this page is talking about: what the followed channel offers, or failing that what
     * installing would fetch. They differ only for a project with no stable build for this server.
     */
    private static ModrinthVersion offered(ProjectView view) {
        return view.latest() != null ? view.latest() : view.installTarget();
    }

    private static Component author(ProjectView view) {

        if (view.author() == null || view.author().isBlank()) {
            return Component.empty();
        }

        return Component.text("by ", MUTED)
                .append(Component.text(view.author(), TEXT))
                .append(Component.text(" · ", MUTED));
    }

    /**
     * The loaders the project publishes for, with the ones this server can actually use picked out.
     */
    private static Component loaders(ProjectView view) {

        List<String> declared = view.project().loaders();

        if (declared == null || declared.isEmpty()) {
            return Component.text("unknown", MUTED);
        }

        TextComponent.Builder out = Component.text();
        boolean first = true;

        for (String loader : declared) {

            if (!first) {
                out.append(Component.text(", ", MUTED));
            }

            out.append(Component.text(loader, view.platformLoaders().contains(loader) ? TEXT : MUTED));
            first = false;
        }

        return out.build();
    }

    private static Component requirements(ProjectView view) {

        TextComponent.Builder out = Component.text();
        boolean first = true;

        for (ProjectView.Requirement requirement : view.requirements()) {

            if (!first) {
                out.append(Component.text(", ", MUTED));
            }

            out.append(Component.text(requirement.name(), requirement.installed() ? TEXT : PENDING)
                    .hoverEvent(HoverEvent.showText(Component.text(
                            requirement.installed() ? "installed" : "not installed",
                            requirement.installed() ? MUTED : PENDING))));

            first = false;
        }

        return out.build();
    }

    private static Component actions(ProjectView view, TrackedPlugin installed) {

        TextComponent.Builder out = line().append(Component.text(INDENT));
        String key = view.project().slug();
        String here = ClickContext.INFO + key;

        if (installed == null) {

            ModrinthVersion target = view.installTarget();

            if (target == null) {
                return out.append(Component.text("No build for this server", MUTED)).build();
            }

            out.append(button("Install", from("/catalog install " + key, here), BRAND,
                    target.versionType() == ReleaseChannel.RELEASE
                            ? "Install " + target.versionNumber()
                            : "Install " + target.versionNumber() + ", the newest build there is — "
                                    + "this project has no stable release for this server"));

            out.append(Component.space())
                    .append(button("Versions", "/catalog versions " + key, MUTED,
                            "Choose a build: the newest release, beta and alpha for this server"));

            return out.build();
        }

        if (view.updateAvailable() && !installed.pendingRestart()) {
            out.append(button("Update", from("/catalog update " + key, here), BRAND,
                    "Stage " + (view.latest() == null ? "the new build" : view.latest().versionNumber())
                            + " for the next restart"))
                    .append(Component.space());
        }

        out.append(button("Switch", "/catalog versions " + key, MUTED, "Choose a different version"))
                .append(Component.space())
                .append(installed.isPinned()
                        ? button("Unhold", from("/catalog unhold " + key, here), MUTED,
                        "Allow updates again")
                        : button("Hold", from("/catalog hold " + key, here), MUTED,
                        "Freeze at the installed version"))
                .append(Component.space());

        if (view.self()) {
            out.append(Component.text("cannot remove itself", MUTED));
        } else {
            out.append(button("Remove", from("/catalog uninstall " + key, here), DANGER,
                    "Move to the trash"));
        }

        return out.build();
    }

    /**
     * A labelled line. The colon is doing real work: the values are multi-coloured, so without it
     * the label runs straight into the first item.
     */
    private static Component field(String label, Component value) {

        return line()
                .append(Component.text(INDENT + label + ":  ", MUTED))
                .append(value)
                .build();
    }

    // --- /catalog versions ------------------------------------------------------------------

    /**
     * The newest build of each channel, to pick one from.
     *
     * <p>One row per channel that has something, and never a history: the choice being made is how
     * stable a build you are willing to run, and older builds of a channel are not part of it.</p>
     */
    public static List<Component> versions(ModrinthProject project, String gameVersion,
                                           Map<ReleaseChannel, ModrinthVersion> newest,
                                           TrackedPlugin installed, boolean offerEverything) {

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text(project.title(), BRAND).decorate(TextDecoration.BOLD))
                .append(Component.text("  newest for " + gameVersion, MUTED))
                .build());

        out.add(Component.empty());

        if (newest.isEmpty()) {
            out.add(Component.text(INDENT + "No build for this server", MUTED));
        }

        for (ReleaseChannel channel : ReleaseChannel.values()) {

            ModrinthVersion version = newest.get(channel);

            if (version != null) {
                out.add(versionRow(project, channel, version, installed));
            }
        }

        out.add(Component.empty());

        TextComponent.Builder footer = line()
                .append(Component.text(INDENT))
                .append(button("Back", "/catalog info " + project.slug(), MUTED,
                        "Back to " + project.title()));

        if (offerEverything) {
            footer.append(Component.space())
                    .append(button("All versions", "/catalog versions " + project.slug() + " --all",
                            PENDING, "Every build ever published, compatible or not"));
        }

        out.add(footer.build());

        return out;
    }

    /**
     * Every build a project has published, offered without any compatibility filter.
     *
     * <p>Behind a config switch and coloured like a warning, because almost everything on this
     * screen will not load. It exists for the operator who knows something Modrinth's metadata does
     * not — that a build works despite what it declares — and it does not pretend otherwise.</p>
     */
    public static List<Component> everyVersion(ModrinthProject project, List<ModrinthVersion> versions,
                                               TrackedPlugin installed, String gameVersion, int page) {

        int pages = Math.max((versions.size() + EVERY - 1) / EVERY, 1);
        int shown = Math.min(Math.max(page, 1), pages);
        int first = (shown - 1) * EVERY;

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text(project.title(), BRAND).decorate(TextDecoration.BOLD))
                .append(Component.text("  " + versions.size() + " builds", MUTED))
                .build());

        out.add(Component.text(INDENT + "Not filtered for this server. Most will not load.", PENDING));
        out.add(Component.empty());

        if (versions.isEmpty()) {
            out.add(Component.text(INDENT + "This project has published nothing", MUTED));
        }

        for (ModrinthVersion version : versions.subList(first, Math.min(first + EVERY, versions.size()))) {
            out.add(everyVersionRow(project, version, installed, gameVersion));
        }

        out.add(Component.empty());

        TextComponent.Builder footer = line()
                .append(Component.text(INDENT + "page ", MUTED))
                .append(Component.text(shown, TEXT))
                .append(Component.text(" of " + pages + "  ", MUTED));

        if (shown > 1) {
            footer.append(button("Newer", everyPage(project, shown - 1), MUTED,
                    "Page " + (shown - 1))).append(Component.space());
        }

        if (shown < pages) {
            footer.append(button("Older", everyPage(project, shown + 1), MUTED,
                    "Page " + (shown + 1))).append(Component.space());
        }

        footer.append(button("Back", "/catalog versions " + project.slug(), MUTED,
                "Back to the builds that run here"));

        out.add(footer.build());

        return out;
    }

    private static String everyPage(ModrinthProject project, int page) {
        return "/catalog versions " + project.slug() + " --all --page " + page;
    }

    /** How many builds one page of the unfiltered list shows. */
    private static final int EVERY = 12;

    private static Component everyVersionRow(ModrinthProject project, ModrinthVersion version,
                                             TrackedPlugin installed, String gameVersion) {

        boolean current = installed != null && version.id().equals(installed.versionId());
        boolean runs = version.gameVersions() != null && version.gameVersions().contains(gameVersion);

        Component hover = Component.text(version.versionNumber(), TEXT)
                .append(Component.newline())
                .append(Component.text("published  ", MUTED))
                .append(Component.text(ago(version.datePublished()), TEXT))
                .append(Component.newline())
                .append(Component.text("for  ", MUTED))
                .append(Component.text(version.gameVersions() == null ? "unknown"
                        : String.join(", ", version.gameVersions()), TEXT))
                .append(Component.newline())
                .append(Component.text("loaders  ", MUTED))
                .append(Component.text(version.loaders() == null ? "unknown"
                        : String.join(", ", version.loaders()), TEXT))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text(current ? "Already installed"
                        : runs ? (installed == null ? "Install this build" : "Switch to this build")
                        : "Use it anyway — this build does not declare " + gameVersion, TEXT));

        TextComponent.Builder row = line()
                .append(Component.text(INDENT))
                .append(Component.text(version.versionNumber(), current ? MUTED : runs ? TEXT : PENDING))
                .append(Component.text("  "))
                .append(channel(version.versionType()));

        if (current) {
            row.append(Component.text("  installed", DONE));
        }

        return row.hoverEvent(HoverEvent.showText(hover))
                .clickEvent(current ? ClickEvent.suggestCommand("/catalog info " + project.slug())
                        : ClickEvent.runCommand(from("/catalog install " + project.slug()
                                + " " + version.id(), ClickContext.INFO + project.slug())))
                .build();
    }

    private static Component versionRow(ModrinthProject project, ReleaseChannel channel,
                                        ModrinthVersion version, TrackedPlugin installed) {

        TextComponent.Builder row = line()
                .append(Component.text(INDENT))
                .append(channel(channel))
                .append(Component.text("  "));

        boolean current = installed != null && version.id().equals(installed.versionId());

        Component hover = Component.text(version.versionNumber(), TEXT)
                .append(Component.newline())
                .append(Component.text("published  ", MUTED))
                .append(Component.text(ago(version.datePublished()), TEXT))
                .append(Component.newline())
                .append(Component.text("for  ", MUTED))
                .append(Component.text(version.gameVersions() == null ? "unknown"
                        : String.join(", ", version.gameVersions()), TEXT))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text(current ? "Already installed"
                        : (installed == null ? "Install this build" : "Switch to this build")
                                + " and follow the " + channel.apiName() + " channel", TEXT));

        row.append(Component.text(version.versionNumber(), current ? MUTED : TEXT));

        if (current) {
            row.append(Component.text("  installed", DONE));
        }

        return row.hoverEvent(HoverEvent.showText(hover))
                .clickEvent(current ? ClickEvent.suggestCommand("/catalog info " + project.slug())
                        : ClickEvent.runCommand(from("/catalog install " + project.slug()
                                + " " + version.id(), ClickContext.INFO + project.slug())))
                .build();
    }

    /**
     * The channel, coloured by how much it is asking of you.
     */
    private static Component channel(ReleaseChannel channel) {

        if (channel == null) {
            return Component.empty();
        }

        return Component.text(channel.apiName(), switch (channel) {
            case RELEASE -> DONE;
            case BETA -> PENDING;
            case ALPHA -> DANGER;
        });
    }

    // --- /catalog search --------------------------------------------------------------------

    public static List<Component> search(String query, SearchResults results, int page,
                                         Set<String> installedProjects) {

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text("Search", BRAND).decorate(TextDecoration.BOLD))
                .append(Component.text("  " + query, TEXT))
                .append(Component.text("  " + results.totalHits() + " results", MUTED))
                .build());

        out.add(Component.empty());

        if (results.hits().isEmpty()) {
            out.add(Component.text(INDENT + "No results", MUTED));
            return out;
        }

        for (SearchHit hit : results.hits()) {
            out.add(hit(hit, installedProjects.contains(hit.projectId())));
        }

        int pages = Math.max((results.totalHits() + PAGE - 1) / PAGE, 1);

        out.add(Component.empty());

        TextComponent.Builder footer = line()
                .append(Component.text(INDENT + "page ", MUTED))
                .append(Component.text(page, TEXT))
                .append(Component.text(" of " + pages + "  ", MUTED));

        if (page > 1) {
            footer.append(button("Back", "/catalog search " + query + " --page " + (page - 1),
                    MUTED, "Page " + (page - 1))).append(Component.space());
        }

        if (results.hasMore()) {
            footer.append(button("Next", "/catalog search " + query + " --page " + (page + 1),
                    BRAND, "Page " + (page + 1)));
        }

        out.add(footer.build());

        return out;
    }

    private static Component hit(SearchHit result, boolean installed) {

        Component hover = Component.text(result.title(), TEXT)
                .append(Component.newline())
                .append(Component.text(result.description() == null ? "" : result.description(), MUTED))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text(result.author() == null ? "" : "by " + result.author(), MUTED))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Open the plugin page", TEXT))
                .append(Component.newline())
                .append(Component.text("/catalog info " + result.slug(), MUTED));

        return line()
                .append(Component.text(INDENT))
                .append(Component.text(result.title(), TEXT))
                .append(Component.text("  " + compact(result.downloads()), MUTED))
                .append(installed ? Component.text("  installed", DONE) : Component.empty())
                .clickEvent(ClickEvent.runCommand("/catalog info " + result.slug()))
                .hoverEvent(HoverEvent.showText(hover))
                .build();
    }

    // --- confirmations and outcomes ---------------------------------------------------------

    public static List<Component> confirmRemove(TrackedPlugin plugin, String from) {

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text("Remove ", DANGER).decorate(TextDecoration.BOLD))
                .append(Component.text(plugin.displayName(), TEXT).decorate(TextDecoration.BOLD))
                .build());

        out.add(Component.text(INDENT + "Moved to trash, unloaded on restart.", MUTED));
        out.add(Component.empty());

        out.add(line()
                .append(Component.text(INDENT))
                .append(button("Confirm", confirming("/catalog uninstall " + key(plugin), from),
                        DANGER, "Remove it now"))
                .append(Component.space())
                .append(button("Cancel", back(from), MUTED, "Leave it installed"))
                .build());

        return out;
    }

    /**
     * Asked before replacing a jar that already works.
     *
     * <p>A rollback says so in its own words. Going backwards is a legitimate thing to want and
     * Catalog will do it, but it is not the same act as taking an update and should not read like
     * one — and the files a plugin has already written are not coming back with it.</p>
     */
    public static List<Component> confirmSwitch(TrackedPlugin plugin, ModrinthVersion version,
                                                boolean older, String from) {

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text(older ? "Roll back " : "Switch ", PENDING)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(plugin.displayName(), TEXT).decorate(TextDecoration.BOLD))
                .build());

        out.add(line()
                .append(Component.text(INDENT))
                .append(Component.text(String.valueOf(plugin.versionNumber()), MUTED))
                .append(Component.text(" → ", MUTED))
                .append(Component.text(version.versionNumber(), TEXT))
                .append(Component.text("  " + (version.versionType() == null ? ""
                        : version.versionType().apiName()), MUTED))
                .build());

        out.add(Component.text(INDENT + "Applied on restart."
                + (older ? " Config and data are not rolled back with it." : ""), MUTED));

        out.add(Component.empty());

        out.add(line()
                .append(Component.text(INDENT))
                .append(button("Confirm", confirming("/catalog install " + key(plugin)
                                + " " + version.id(), from),
                        PENDING, older ? "Roll back now" : "Switch now"))
                .append(Component.space())
                // Back to the picker rather than to wherever the payload points: a switch is only
                // ever chosen from there, and the payload is aimed at where to land afterwards.
                .append(button("Cancel", "/catalog versions " + key(plugin), MUTED,
                        "Leave it as it is"))
                .build());

        return out;
    }

    /**
     * Where Cancel goes: back to whatever the removal was started from.
     *
     * <p>Redrawing that screen is also what abandons the pending confirmation, so cancelling and
     * then clicking the same button again asks a second time rather than going straight through.</p>
     */
    private static String back(String from) {

        if (from != null && from.startsWith(ClickContext.INFO)) {
            return "/catalog info " + from.substring(ClickContext.INFO.length());
        }

        return "/catalog list";
    }

    public static Component staged(String name, String version) {
        return line()
                .append(Component.text(name + " " + version, TEXT))
                .append(Component.text(" staged, applies on restart", MUTED))
                .build();
    }

    public static Component stagedAll(int count) {
        return line()
                .append(Component.text(count, DONE))
                .append(Component.text(count == 1 ? " update staged, applies on restart"
                        : " updates staged, apply on restart", MUTED))
                .build();
    }

    /**
     * Said when someone asks to install something they already have, which is nearly always a
     * request to change build rather than a mistake.
     */
    public static Component alreadyInstalled(TrackedPlugin plugin) {
        return line()
                .append(Component.text(plugin.displayName(), TEXT))
                .append(Component.text(" is already installed  ", MUTED))
                .append(button("Versions", "/catalog versions " + key(plugin), BRAND,
                        "Pick a different build to switch to"))
                .build();
    }

    public static Component installed(String name, String version) {
        return line()
                .append(Component.text(name + " " + version, TEXT))
                .append(Component.text(" installed, loads on restart", MUTED))
                .build();
    }

    public static Component removed(String name, boolean deleted) {
        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(deleted ? " removed, unloads on restart"
                        : " removed, file is locked and is deleted on shutdown", MUTED))
                .build();
    }

    public static Component channelSet(String name, ReleaseChannel channel) {
        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(" now follows ", MUTED))
                .append(Component.text(channel.apiName(), BRAND))
                .build();
    }

    public static Component held(String name, boolean held) {
        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(held ? " held at this version" : " no longer held", MUTED))
                .build();
    }

    public static Component reloaded() {
        return Component.text("Configuration reloaded", MUTED);
    }

    public static Component upToDate() {
        return Component.text("Nothing to update", MUTED);
    }

    public static Component noUpdate(String name) {
        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(" is already up to date", MUTED))
                .build();
    }

    public static Component unknownPlugin(String query) {
        return line()
                .append(Component.text("No plugin found for ", DANGER))
                .append(Component.text(query, TEXT))
                .build();
    }

    public static Component cannotRemoveSelf(String name) {
        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(" cannot remove itself. Delete the jar by hand.", MUTED))
                .build();
    }

    public static Component nothingTracked() {
        return Component.text("No plugins tracked", MUTED);
    }

    public static Component failed(String reason) {
        return Component.text(reason, DANGER);
    }

    // --- plumbing ---------------------------------------------------------------------------

    /**
     * A clickable label wrapped in brackets, so it reads as a button rather than as prose.
     *
     * <p>A command ending in a space is offered for the player to complete rather than run.</p>
     */
    private static Component button(String label, String command, TextColor colour, String description) {

        boolean complete = !command.endsWith(" ");

        return Component.text("[", MUTED)
                .append(Component.text(label, colour))
                .append(Component.text("]", MUTED))
                .clickEvent(complete ? ClickEvent.runCommand(command) : ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(explain(description, command)));
    }

    private static Component icon(String glyph, TextColor colour, String command, String description) {

        return Component.text("[", MUTED)
                .append(Component.text(glyph, colour))
                .append(Component.text("]", MUTED))
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(explain(description, command)));
    }

    /**
     * What a click will do, and only then how it does it.
     *
     * <p>The payload that tells Catalog which screen the button was on is left out: it is not part
     * of the command anyone would type, and showing it would only invite someone to.</p>
     */
    private static Component explain(String description, String command) {

        return Component.text(description, TEXT)
                .append(Component.newline())
                .append(Component.text(ClickContext.strip(command).trim(), MUTED));
    }

    /**
     * Tags a command with the screen it is being offered from.
     */
    private static String from(String command, String screen) {
        return command + " " + ClickContext.MARKER + screen;
    }

    /**
     * Tags a command as the confirmation of one already asked about.
     *
     * <p>What makes it a confirmation is this payload, not the fact that the command has been run
     * before — so pressing the button that asked cannot double as the answer.</p>
     */
    private static String confirming(String command, String screen) {
        return from(command, ClickContext.CONFIRM + (screen == null ? "" : screen));
    }

    /**
     * Starts a line with no styling of its own, so nothing a child sets is inherited by its
     * siblings. Bold titles next to unbolded counts depend on it.
     */
    private static TextComponent.Builder line() {
        return Component.text();
    }

    private static String key(TrackedPlugin plugin) {
        return plugin.slug() != null ? plugin.slug() : plugin.displayName();
    }

    static String compact(int count) {

        if (count >= 1_000_000) {
            return Math.round(count / 100_000.0) / 10.0 + "M";
        }

        if (count >= 10_000) {
            return (count / 1_000) + "K";
        }

        if (count >= 1_000) {
            return Math.round(count / 100.0) / 10.0 + "K";
        }

        return String.valueOf(count);
    }

    private static String ago(Instant when) {

        if (when == null) {
            return "unknown";
        }

        long minutes = Duration.between(when, Instant.now()).toMinutes();

        if (minutes < 60) {
            return Math.max(minutes, 0) + "m ago";
        }

        long hours = minutes / 60;
        return hours < 24 ? hours + "h ago" : (hours / 24) + "d ago";
    }

}
