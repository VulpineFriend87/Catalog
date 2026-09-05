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
import top.vulpine.catalog.trash.model.TrashEntry;
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
     * The plugin's own card: what it is, and nothing else.
     *
     * <p>Says nothing about the server's plugins — that is {@code /catalog list} — and nothing
     * about how to drive it, which is {@code /catalog help}.</p>
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

        // The one pointer that has to be here, or nothing else is findable.
        out.add(entry("help", "", "Every command"));

        return out;
    }

    /**
     * The whole command surface.
     *
     * <p>Grouped by blank lines rather than headings — reading, then changing what is installed,
     * then changing what a plugin does on its own. Labelling the groups would cost three more lines
     * to say what the spacing already says.</p>
     */
    public static List<Component> help() {

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text("Catalog", BRAND).decorate(TextDecoration.BOLD))
                .append(Component.text("  commands", MUTED))
                .build());

        out.add(Component.empty());

        out.add(entry("list", "", "Managed plugins"));
        out.add(entry("info", "<plugin>", "Full details, installed or not"));
        out.add(entry("search", "<query>", "Find plugins on Modrinth"));
        out.add(entry("versions", "<plugin>", "Newest build of each channel"));

        out.add(Component.empty());

        out.add(entry("install", "<slug> [version]", "Add a plugin"));
        out.add(entry("update", "<plugin|all>", "Download and stage updates"));
        out.add(entry("uninstall", "<plugin>", "Move a plugin to the trash"));
        out.add(entry("trash", "", "Put back something you removed"));

        out.add(Component.empty());

        out.add(entry("settings", "<plugin>", "What a plugin does on its own"));
        out.add(entry("channel", "<plugin> <channel>", "Which builds to follow"));
        out.add(entry("auto", "<plugin> <on|off>", "Update without asking"));
        out.add(entry("soak", "<plugin> <window>", "How long a build must be public first"));
        out.add(entry("hold", "<plugin>", "Freeze a plugin at its version"));
        out.add(entry("unhold", "<plugin>", "Allow updates again"));

        out.add(Component.empty());

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

        footer.append(button("Search", "/catalog search ", MUTED, "Search Modrinth"))
                .append(Component.space())
                .append(button("Trash", "/catalog trash", MUTED, "Plugins you have removed"));

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
                .append(button("Settings", "/catalog settings " + key, MUTED,
                        "Channel, auto-update and whether it is held"))
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

    // --- /catalog settings ------------------------------------------------------------------

    /** The soak windows offered as one click each. Anything else can still be typed. */
    private static final int[] SOAK_PRESETS = {0, 30, 120, 360, 1440};

    /**
     * What a plugin does on its own, as four rows of choices.
     *
     * <p>Separate from the project page because these are settings rather than actions: nothing
     * here happens now, it decides what happens later. Mixing them into a row of verbs is what made
     * the channel picker feel wrong when it lived there.</p>
     */
    public static List<Component> settings(TrackedPlugin plugin, int defaultSoak) {

        String key = key(plugin);
        String here = ClickContext.SETTINGS + key;

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text(plugin.displayName(), BRAND).decorate(TextDecoration.BOLD))
                .append(Component.text("  settings", MUTED))
                .build());

        // The installed build and the channel it follows, together: running a beta while following
        // release means nothing will be offered until a release overtakes it, and that looks like
        // nothing happening unless both are on screen.
        out.add(line()
                .append(Component.text(INDENT + String.valueOf(plugin.versionNumber()), MUTED))
                .append(Component.text(" · ", MUTED))
                .append(channel(plugin.channel()))
                .build());

        out.add(Component.empty());

        out.add(setting("Channel", channelChoices(plugin, key)));
        out.add(setting("Auto-update", autoChoices(plugin, key)));

        // Only meaningful when Catalog is the one deciding to install.
        if (plugin.autoUpdate()) {
            out.add(setting("Soak", soakChoices(plugin, key, defaultSoak)));
        }

        out.add(setting("Updates", holdChoices(plugin, key, here)));

        out.add(Component.empty());

        out.add(line()
                .append(Component.text(INDENT))
                .append(button("Back", "/catalog info " + key, MUTED, "Back to " + plugin.displayName()))
                .build());

        return out;
    }

    private static Component channelChoices(TrackedPlugin plugin, String key) {

        TextComponent.Builder out = Component.text();

        for (ReleaseChannel channel : ReleaseChannel.values()) {
            out.append(choice(channel.apiName(), "/catalog channel " + key + " " + channel.apiName(),
                    channel == plugin.channel(),
                    channel == ReleaseChannel.RELEASE
                            ? "Only offer stable builds"
                            : "Offer " + channel.apiName() + " builds, and anything more stable"));
        }

        return out.build();
    }

    /**
     * No payload on this one, nor on the channel row: both commands take a value after the plugin
     * name, so neither is greedy and the client refuses to parse anything trailing. They find their
     * way back to this screen on their own.
     */
    private static Component autoChoices(TrackedPlugin plugin, String key) {

        return Component.text()
                .append(choice("on", "/catalog auto " + key + " on", plugin.autoUpdate(),
                        "Install updates without asking, once they have soaked"))
                .append(choice("off", "/catalog auto " + key + " off", !plugin.autoUpdate(),
                        "Only update when you say so"))
                .build();
    }

    private static Component soakChoices(TrackedPlugin plugin, String key, int defaultSoak) {

        boolean inherits = plugin.soakMinutes() == TrackedPlugin.INHERIT_SOAK;

        TextComponent.Builder out = Component.text()
                .append(choice("default", "/catalog soak " + key + " default", inherits,
                        "Follow the config, currently " + soakLabel(defaultSoak)));

        boolean custom = !inherits;

        for (int minutes : SOAK_PRESETS) {

            boolean selected = !inherits && plugin.soakMinutes() == minutes;
            custom &= !selected;

            out.append(choice(soakLabel(minutes), "/catalog soak " + key + " " + minutes, selected,
                    minutes == 0
                            ? "Install as soon as a build appears"
                            : "Wait " + soakLabel(minutes) + " after a build is published"));
        }

        // A window someone typed that is not one of the presets still has to be visible.
        if (custom) {
            out.append(chosen(soakLabel(plugin.soakMinutes())));
        }

        return out.build();
    }

    private static Component holdChoices(TrackedPlugin plugin, String key, String here) {

        return Component.text()
                .append(choice("offered", from("/catalog unhold " + key, here), !plugin.isPinned(),
                        "Let this plugin be updated"))
                .append(choice("held", from("/catalog hold " + key, here), plugin.isPinned(),
                        "Freeze it at " + plugin.versionNumber() + " and stop offering updates"))
                .build();
    }

    /**
     * One value of a setting: the chosen one stands out and does nothing, the rest are clickable.
     */
    private static Component choice(String label, String command, boolean selected, String description) {

        if (selected) {
            return chosen(label);
        }

        return Component.text(label, MUTED)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(explain(description, command)))
                .append(Component.text("  ", MUTED));
    }

    /** The value a setting currently holds: stands out, and does nothing when clicked. */
    private static Component chosen(String label) {
        return Component.text(label, BRAND).append(Component.text("  ", MUTED));
    }

    private static Component setting(String label, Component values) {

        return line()
                .append(Component.text(INDENT + label + ":  ", MUTED))
                .append(values)
                .build();
    }

    /**
     * A soak window as something readable, since minutes stop meaning anything past an hour or two.
     */
    static String soakLabel(int minutes) {

        if (minutes <= 0) {
            return "none";
        }

        if (minutes < 60) {
            return minutes + "m";
        }

        return minutes % 60 == 0 ? (minutes / 60) + "h" : (minutes / 60) + "h" + (minutes % 60) + "m";
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

    // --- /catalog trash ---------------------------------------------------------------------

    /** How many removals one page of the trash shows. */
    private static final int BIN = 10;

    /**
     * What has been removed and can still be put back.
     *
     * <p>Ordered by when it was removed rather than by name: the thing somebody wants back is
     * almost always the last thing they got rid of.</p>
     */
    public static List<Component> trash(List<TrashEntry> entries, int retentionDays, int page) {

        int pages = Math.max((entries.size() + BIN - 1) / BIN, 1);
        int shown = Math.min(Math.max(page, 1), pages);
        int first = (shown - 1) * BIN;

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text("Trash", BRAND).decorate(TextDecoration.BOLD))
                .append(Component.text("  " + entries.size(), TEXT))
                .append(Component.text(" removed", MUTED))
                .build());

        if (entries.isEmpty()) {
            out.add(Component.empty());
            out.add(Component.text(INDENT + "Nothing has been removed", MUTED));
            return out;
        }

        out.add(Component.text(INDENT + (retentionDays > 0
                ? "Deleted after " + retentionDays + " days."
                : "Kept until you delete them."), MUTED));

        out.add(Component.empty());

        for (TrashEntry entry : entries.subList(first, Math.min(first + BIN, entries.size()))) {
            out.add(trashRow(entry));
        }

        out.add(Component.empty());

        TextComponent.Builder footer = line()
                .append(Component.text(INDENT + "page ", MUTED))
                .append(Component.text(shown, TEXT))
                .append(Component.text(" of " + pages + "  ", MUTED));

        if (shown > 1) {
            footer.append(button("Newer", "/catalog trash --page " + (shown - 1), MUTED,
                    "Page " + (shown - 1))).append(Component.space());
        }

        if (shown < pages) {
            footer.append(button("Older", "/catalog trash --page " + (shown + 1), MUTED,
                    "Page " + (shown + 1))).append(Component.space());
        }

        footer.append(button("Empty", from("/catalog trash delete all", ClickContext.TRASH),
                        DANGER, "Delete everything in the trash"))
                .append(Component.space())
                .append(button("Plugins", "/catalog list", MUTED, "Back to the plugin list"));

        out.add(footer.build());

        return out;
    }

    private static Component trashRow(TrashEntry entry) {

        Component hover = Component.text(entry.displayName(), TEXT)
                .append(Component.newline())
                .append(Component.text(entry.fileName(), MUTED))
                .append(entry.versionNumber() == null ? Component.empty()
                        : Component.newline().append(Component.text(entry.versionNumber(), MUTED)))
                .append(entry.removedBy() == null ? Component.empty()
                        : Component.newline().append(Component.text("removed by " + entry.removedBy(), MUTED)))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text(entry.projectId() == null
                        ? "Not a Modrinth plugin. Restoring only puts the file back."
                        : "Restoring puts it back and tracks it again.", MUTED));

        return line()
                .append(Component.text(INDENT))
                .append(Component.text(entry.displayName(), TEXT).hoverEvent(HoverEvent.showText(hover)))
                .append(entry.versionNumber() == null ? Component.empty()
                        : Component.text("  " + entry.versionNumber(), MUTED))
                .append(Component.text("  " + ago(entry.removedAt()), MUTED))
                .append(Component.space())
                .append(button("Restore", restoreCommand(entry), BRAND,
                        "Put " + entry.displayName() + " back"))
                .append(Component.space())
                .append(icon("×", DANGER, from("/catalog trash delete " + entry.storedAs(),
                                ClickContext.TRASH),
                        "Delete " + entry.displayName() + " permanently"))
                .build();
    }

    /**
     * The command that puts one removal back.
     *
     * <p>Keyed on the name the jar is filed under, which is unique per removal — so an undo offered
     * ten minutes and three removals ago still means the one it was offered for, and can never put
     * back somebody else's plugin.</p>
     */
    private static String restoreCommand(TrashEntry entry) {
        return "/catalog trash restore " + entry.storedAs();
    }

    /**
     * Asked before emptying the bin, which is the one action here that cannot be undone.
     *
     * <p>Deleting a single removal is not asked about: it was already removed once deliberately,
     * and the row says what it is. Deleting all of them at once is a different size of mistake.</p>
     */
    public static List<Component> confirmEmpty(int count) {

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text("Empty the trash", DANGER).decorate(TextDecoration.BOLD))
                .build());

        out.add(line()
                .append(Component.text(INDENT))
                .append(Component.text(count, TEXT))
                .append(Component.text(count == 1 ? " removal. This cannot be undone."
                        : " removals. This cannot be undone.", MUTED))
                .build());

        out.add(Component.empty());

        out.add(line()
                .append(Component.text(INDENT))
                .append(button("Confirm", confirming("/catalog trash delete all", ClickContext.TRASH),
                        DANGER, "Delete them now"))
                .append(Component.space())
                .append(button("Cancel", "/catalog trash", MUTED, "Keep them"))
                .build());

        return out;
    }

    // --- confirmations and outcomes ---------------------------------------------------------

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

    /**
     * What happened, and the way back.
     *
     * <p>Undo instead of a confirmation asked beforehand: removing a plugin does not take effect
     * until a restart, so there is a whole window in which the decision costs nothing to reverse.
     * The button carries the removal it belongs to, so an old one left further up the chat still
     * undoes its own removal rather than the most recent.</p>
     */
    public static Component removed(String name, boolean deleted, TrashEntry entry) {

        TextComponent.Builder out = line()
                .append(Component.text(name, TEXT))
                .append(Component.text(deleted ? " moved to trash, unloads on restart"
                        : " moved to trash, file is locked and goes on shutdown", MUTED));

        if (entry != null) {
            out.append(Component.space()).append(button("Undo", restoreCommand(entry), BRAND,
                    "Put " + name + " back"));
        }

        return out.build();
    }

    public static Component restored(String name, boolean tracked) {
        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(tracked ? " restored" : " restored, not tracked", MUTED))
                .build();
    }

    /**
     * Why an undo did nothing: the removal it points at is not there any more.
     */
    public static Component nothingToRestore() {
        return Component.text("That removal is not in the trash any more", MUTED);
    }

    public static Component discarded(String name) {
        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(" deleted", MUTED))
                .build();
    }

    public static Component emptied(int count) {
        return line()
                .append(Component.text(count, TEXT))
                .append(Component.text(count == 1 ? " removal deleted"
                        : " removals deleted", MUTED))
                .build();
    }

    public static Component trashAlreadyEmpty() {
        return Component.text("The trash is already empty", MUTED);
    }

    public static Component channelSet(String name, ReleaseChannel channel) {
        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(" now follows ", MUTED))
                .append(Component.text(channel.apiName(), BRAND))
                .build();
    }

    public static Component autoSet(String name, boolean on) {
        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(on ? " updates itself once builds have soaked"
                        : " only updates when you say so", MUTED))
                .build();
    }

    public static Component soakSet(String name, int minutes, int defaultSoak) {

        boolean inherits = minutes == TrackedPlugin.INHERIT_SOAK;

        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(" waits ", MUTED))
                .append(Component.text(soakLabel(inherits ? defaultSoak : minutes), BRAND))
                .append(Component.text(inherits ? " before updating itself, following the config"
                        : " before updating itself", MUTED))
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
