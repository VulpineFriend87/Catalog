package top.vulpine.catalog.paper.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import top.vulpine.catalog.history.Event;
import top.vulpine.catalog.history.HistoryEntry;
import top.vulpine.catalog.modrinth.model.DependencyType;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * All chat messages the plugin sends.
 */
public final class Messages {

    /** How many search results one page shows. */
    public static final int PAGE = 9;

    /** Catalog's color. */
    private static final TextColor BRAND = TextColor.color(0xC08CFF);

    /** Something waiting to be applied. */
    private static final TextColor PENDING = TextColor.color(0xF2C46B);

    /** Destructive. */
    private static final TextColor DANGER = TextColor.color(0xFF7B72);

    /** Done. */
    private static final TextColor DONE = TextColor.color(0x7BE38B);

    private static final NamedTextColor TEXT = NamedTextColor.WHITE;
    private static final NamedTextColor MUTED = NamedTextColor.GRAY;

    private static final String INDENT = "  ";

    private Messages() {}

    // --- /catalog ---------------------------------------------------------------------------

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

        out.add(entry("help", "", "Every command"));

        return out;
    }

    // --- /catalog help ----------------------------------------------------------------------

    public static List<Component> help() {

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text("Catalog", BRAND).decorate(TextDecoration.BOLD))
                .append(Component.text("  commands", MUTED))
                .build());

        out.add(Component.empty());

        out.add(entry("list", "", "Managed plugins"));
        out.add(entry("info", "<plugin>", "Details about a plugin"));
        out.add(entry("search", "<query>", "Search plugins on Modrinth"));
        out.add(entry("versions", "<plugin>", "Newest build of each channel"));

        out.add(Component.empty());

        out.add(entry("install", "<slug> [version]", "Install a plugin"));
        out.add(entry("update", "<plugin|all>", "Update a plugin"));
        out.add(entry("cancel", "<plugin>", "Drop a staged update"));
        out.add(entry("history", "", "What Catalog has done"));
        out.add(entry("uninstall", "<plugin>", "Move a plugin to the trash"));
        out.add(entry("trash", "", "Restore a trashed plugin"));

        out.add(Component.empty());

        out.add(entry("settings", "<plugin>", "Open settings for a plugin"));
        out.add(entry("channel", "<plugin> <channel>", "Which builds to follow"));
        out.add(entry("auto", "<plugin> <on|off>", "Enable/disable auto-updates"));
        out.add(entry("soak", "<plugin> <window>", "Change soak time for a plugin"));
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
                    BRAND, "Update all plugins")).append(Component.space());
        }

        footer.append(button("Search", "/catalog search ", MUTED, "Search Modrinth"))
                .append(Component.space())
                .append(button("Trash", "/catalog trash", MUTED, "Show trashed plugins"))
                .append(Component.space())
                .append(button("History", "/catalog history", MUTED, "What Catalog has done"));

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
                .clickEvent(ClickEvent.runCommand(from("/catalog info " + key(plugin),
                        ClickContext.LIST)))
                .hoverEvent(HoverEvent.showText(hover));
    }

    /**
     * Which kind of restart a plugin is waiting for, if any.
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
            out.add(field("Needs", requirements(view.requirements())));
        }

        if (!view.optionals().isEmpty()) {
            out.add(field("Optional", requirements(view.optionals())));
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

    private static Component requirements(List<ProjectView.Requirement> requirements) {

        TextComponent.Builder out = Component.text();
        boolean first = true;

        for (ProjectView.Requirement requirement : requirements) {

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

        String key = view.project().slug();
        String here = ClickContext.INFO + key;

        List<Component> row = new ArrayList<>();

        if (installed == null) {

            ModrinthVersion target = view.installTarget();

            if (target == null) {
                return line().append(Component.text(INDENT))
                        .append(Component.text("No build for this server", MUTED))
                        .build();
            }

            row.add(button("Install", from("/catalog install " + key, here), BRAND,
                    "Install " + target.versionNumber()));

            row.add(button("Versions", from("/catalog versions " + key, here), MUTED,
                    "Choose a build: the newest release, beta and alpha for this server"));

            if (view.declaresAnything()) {
                row.add(button("Dependencies", from("/catalog dependencies " + key, here), MUTED,
                        "Show dependencies"));
            }

            return buttons(row);
        }

        if (installed.pendingRestart()) {

            row.add(button("Cancel update", from("/catalog cancel " + key, here), PENDING,
                    "Leave " + installed.versionNumber() + " in place"));

        } else if (view.updateAvailable()) {
            row.add(button("Update", from("/catalog update " + key, here), BRAND,
                    "Stage " + (view.latest() == null ? "the new build" : view.latest().versionNumber())
                            + " for the next restart"));
        }

        row.add(button("Switch", from("/catalog versions " + key, here), MUTED,
                "Choose a different version"));

        if (view.declaresAnything()) {
            row.add(button("Dependencies", from("/catalog dependencies " + key, here), MUTED,
                    "Show dependencies"));
        }

        row.add(button("Settings", "/catalog settings " + key, MUTED,
                "Open the settings page"));

        row.add(view.self()
                ? Component.empty()
                : button("Remove", from("/catalog uninstall " + key, here), DANGER,
                        "Move to the trash"));

        return buttons(row);
    }

    private static Component field(String label, Component value) {

        return line()
                .append(Component.text(INDENT + label + ":  ", MUTED))
                .append(value)
                .build();
    }

    // --- /catalog settings ------------------------------------------------------------------

    private static final int[] SOAK_PRESETS = {0, 30, 120, 360, 1440};

    public static List<Component> settings(TrackedPlugin plugin, int defaultSoak, String from) {

        String key = key(plugin);
        String here = ClickContext.SETTINGS + key;

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text(plugin.displayName(), BRAND).decorate(TextDecoration.BOLD))
                .append(Component.text("  settings", MUTED))
                .build());

        out.add(line()
                .append(Component.text(INDENT + plugin.versionNumber(), MUTED))
                .append(Component.text(" · ", MUTED))
                .append(channel(plugin.channel()))
                .build());

        out.add(Component.empty());

        out.add(setting("Channel", channelChoices(plugin, key, here)));
        out.add(setting("Auto-update", autoChoices(plugin, key, here)));

        // Only meaningful when Catalog is the one deciding to install.
        if (plugin.autoUpdate()) {
            out.add(setting("Soak", soakChoices(plugin, key, defaultSoak, here)));
        }

        out.add(setting("Updates", holdChoices(plugin, key, here)));

        out.add(Component.empty());

        out.add(line()
                .append(Component.text(INDENT))
                .append(button("Back", backTo(from, "/catalog info " + key), MUTED, "Back"))
                .build());

        return out;
    }

    private static Component channelChoices(TrackedPlugin plugin, String key, String here) {

        TextComponent.Builder out = Component.text();

        for (ReleaseChannel channel : ReleaseChannel.values()) {
            out.append(choice(channel.apiName(), "/catalog channel " + key + " " + channel.apiName(),
                    channel == plugin.channel(),
                    channel == ReleaseChannel.RELEASE
                            ? "Only offer stable builds"
                            : "Offer " + channel.apiName() + " builds, and anything more stable",
                    here));
        }

        return out.build();
    }

    private static Component autoChoices(TrackedPlugin plugin, String key, String here) {

        return Component.text()
                .append(choice("on", "/catalog auto " + key + " on", plugin.autoUpdate(),
                        "Install updates automatically", here))
                .append(choice("off", "/catalog auto " + key + " off", !plugin.autoUpdate(),
                        "Only update manually", here))
                .build();
    }

    private static Component soakChoices(TrackedPlugin plugin, String key, int defaultSoak,
                                         String here) {

        boolean inherits = plugin.soakMinutes() == TrackedPlugin.INHERIT_SOAK;

        TextComponent.Builder out = Component.text()
                .append(choice("default", "/catalog soak " + key + " default", inherits,
                        "Follow the config, currently " + soakLabel(defaultSoak), here));

        boolean custom = !inherits;

        for (int minutes : SOAK_PRESETS) {

            boolean selected = !inherits && plugin.soakMinutes() == minutes;
            custom &= !selected;

            out.append(choice(soakLabel(minutes), "/catalog soak " + key + " " + minutes, selected,
                    minutes == 0
                            ? "Install as soon as a build appears"
                            : "Wait " + soakLabel(minutes) + " after a build is published", here));
        }

        // A window someone typed that is not one of the presets still has to be visible.
        if (custom) {
            out.append(chosen(soakLabel(plugin.soakMinutes())));
        }

        return out.build();
    }

    private static Component holdChoices(TrackedPlugin plugin, String key, String here) {

        return Component.text()
                .append(choice("offered", "/catalog unhold " + key, !plugin.isPinned(),
                        "Let this plugin be updated", here))
                .append(choice("held", "/catalog hold " + key, plugin.isPinned(),
                        "Freeze it at " + plugin.versionNumber() + " and stop offering updates", here))
                .build();
    }

    private static Component choice(String label, String command, boolean selected,
                                    String description, String here) {

        if (selected) {
            return chosen(label);
        }

        return Component.text(label, MUTED)
                .clickEvent(ClickEvent.runCommand(from(command, here)))
                .hoverEvent(HoverEvent.showText(explain(description, command)))
                .append(Component.text("  ", MUTED));
    }

    /** The value a setting currently holds. */
    private static Component chosen(String label) {
        return Component.text(label, BRAND).append(Component.text("  ", MUTED));
    }

    private static Component setting(String label, Component values) {

        return line()
                .append(Component.text(INDENT + label + ":  ", MUTED))
                .append(values)
                .build();
    }

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
     * The newest build of each channel to pick one from.
     */
    public static List<Component> versions(ModrinthProject project, String gameVersion,
                                           Map<ReleaseChannel, ModrinthVersion> newest,
                                           TrackedPlugin installed, boolean offerEverything,
                                           String from) {

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
                .append(button("Back", backTo(from, "/catalog info " + project.slug()), MUTED,
                        "Back"));

        if (offerEverything) {
            footer.append(Component.space())
                    .append(button("All versions", "/catalog versions " + project.slug() + " --all",
                            PENDING, "Every build ever published"));
        }

        out.add(footer.build());

        return out;
    }

    /**
     * Every build a project has published unfiltered.
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
                "Back to the builds that run on this server"));

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
                        : "Use it anyway. This build does not declare " + gameVersion, TEXT));

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
        boolean staged = installed != null && version.id().equals(installed.stagedVersionId());
        boolean waiting = installed != null && installed.pendingRestart();

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
                .append(Component.text(staged ? "Waiting for a restart"
                        : current && waiting ? "Cancel the staged update and stay here"
                        : current ? "Already installed"
                        : (installed == null ? "Install this build" : "Switch to this build")
                                + " and follow the " + channel.apiName() + " channel", TEXT));

        row.append(Component.text(version.versionNumber(), current ? MUTED : TEXT));

        if (staged) {
            row.append(Component.text("  restart", PENDING));
        } else if (current) {
            row.append(Component.text("  installed", DONE));
        }

        String here = ClickContext.INFO + project.slug();

        // While a build is waiting, the installed row is the way back out of it.
        String command = staged ? null
                : current && waiting ? from("/catalog cancel " + project.slug(), here)
                : current ? null
                : from("/catalog install " + project.slug() + " " + version.id(), here);

        return row.hoverEvent(HoverEvent.showText(hover))
                .clickEvent(command == null
                        ? ClickEvent.suggestCommand("/catalog info " + project.slug())
                        : ClickEvent.runCommand(command))
                .build();
    }

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
                .clickEvent(ClickEvent.runCommand(from("/catalog info " + result.slug(), null)))
                .hoverEvent(HoverEvent.showText(hover))
                .build();
    }

    // --- /catalog dependencies -----------------------------------------------------------------------

    public static List<Component> dependencies(ModrinthProject project, List<DependencyView> rows,
                                               boolean installed, boolean installable,
                                               String switchingTo, String from) {

        String key = project.slug();
        String here = ClickContext.DEPENDENCIES + key;

        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text(project.title(), BRAND).decorate(TextDecoration.BOLD))
                .append(Component.text("  dependencies", MUTED))
                .build());

        int missing = 0;
        boolean reachable = true;

        for (DependencyView row : rows) {

            if (row.blocking()) {
                missing++;
                reachable &= row.available();
            }
        }

        if (missing > 0) {
            out.add(line()
                    .append(Component.text(INDENT))
                    .append(Component.text(missing, PENDING))
                    .append(Component.text(" required missing", MUTED))
                    .build());
        }

        out.add(Component.empty());

        if (rows.isEmpty()) {
            out.add(Component.text(INDENT + "Declares none", MUTED));
        }

        for (DependencyView row : rows) {
            out.add(dependencyRow(row, here));
        }

        out.add(Component.empty());
        out.add(dependencyActions(project, installed, installable, missing, reachable,
                switchingTo, here, from));

        return out;
    }

    private static Component dependencyActions(ModrinthProject project, boolean installed,
                                               boolean installable, int missing, boolean reachable,
                                               String switchingTo, String here, String from) {

        String key = project.slug();

        // A switch names the build, so pressing through this screen lands on the one that was
        // chosen rather than on whatever installing would pick.
        String install = "/catalog install " + key
                + (switchingTo == null ? "" : " " + switchingTo);


        List<Component> row = new ArrayList<>();

        if (missing > 0 && (!installed || switchingTo != null)) {

            // Hidden when a requirement has no build here: installing the rest would leave exactly
            // the broken server this screen exists to prevent.
            if (reachable) {

                // The count says how many files this writes, which is what "all" left open: the
                // screen lists optional rows too, and those are never part of it.
                row.add(button("Install all", intent(install,
                                ClickContext.WITH_DEPENDENCIES, here), BRAND,
                        installed
                                ? "Install the " + missing + " missing, then switch"
                                : "Install " + project.title() + " and the " + missing
                                        + " it requires"));
            }

            row.add(button(installed ? "Switch anyway" : "Just " + project.title(),
                    intent(install, ClickContext.ALONE, here), PENDING,
                    installed
                            ? "Switch without installing what it needs"
                            : "Install " + project.title() + " on its own"));

        } else if (!installed && installable) {

            row.add(button("Install " + project.title(), from("/catalog install " + key, here),
                    BRAND, "Install " + project.title()));

        } else if (installed && missing > 0 && reachable) {

            row.add(button("Install required",
                    from("/catalog dependencies " + key + " --install", here), BRAND,
                    "Install the " + missing + " missing"));
        }

        row.add(button("Back", backTo(from, "/catalog info " + key), MUTED, "Back"));

        return buttons(row);
    }

    private static Component dependencyRow(DependencyView row, String here) {

        TextComponent.Builder line = line().append(Component.text(INDENT));

        if (row.conflicting()) {
            return line
                    .append(Component.text("  "))
                    .append(Component.text(row.name(), DANGER))
                    .append(Component.text("  incompatible, installed", MUTED))
                    .build();
        }

        boolean optional = row.type() == DependencyType.OPTIONAL;
        String kind = optional ? "  optional" : "  required";

        if (row.installed()) {
            return line
                    .append(Component.text("✔ ", DONE))
                    .append(Component.text(row.name(), TEXT))
                    .append(Component.text(kind, MUTED))
                    .append(Component.text(row.version() == null ? "" : "  " + row.version(), MUTED))
                    .build();
        }

        line.append(Component.text("  "))
                .append(Component.text(row.name(), optional ? MUTED : PENDING))
                .append(Component.text(kind, MUTED))
                .append(Component.space());

        if (!row.available()) {
            return line.append(Component.text(" no build for this server", DANGER)).build();
        }

        return line
                .append(button("Install", from("/catalog install " + row.slug(), here), BRAND,
                        "Install " + row.name() + " " + row.version()))
                .append(Component.space())
                .append(button("Versions", from("/catalog versions " + row.slug(), here), MUTED,
                        "Choose a build of " + row.name()))
                .build();
    }

    /**
     * The command that reopens the screen something was launched from.
     *
     * @param from     the screen token a payload carried, or null when there was none
     * @param fallback where to go when nothing was carried
     */
    private static String backTo(String from, String fallback) {

        if (from == null || from.isEmpty()) {
            return fallback;
        }

        if (from.equals(ClickContext.LIST)) {
            return "/catalog list";
        }

        if (from.equals(ClickContext.TRASH)) {
            return "/catalog trash";
        }

        if (from.startsWith(ClickContext.INFO)) {
            return "/catalog info " + from.substring(ClickContext.INFO.length());
        }

        if (from.startsWith(ClickContext.SETTINGS)) {
            return "/catalog settings " + from.substring(ClickContext.SETTINGS.length());
        }

        if (from.startsWith(ClickContext.DEPENDENCIES)) {
            return "/catalog dependencies " + from.substring(ClickContext.DEPENDENCIES.length());
        }

        return fallback;
    }

    private static String back(String from) {
        return backTo(from, "/catalog list");
    }

    /**
     * Tags a command with what it is an answer to, wrapping the screen it was asked from.
     */
    private static String intent(String command, String marker, String screen) {
        return from(command, marker + (screen == null ? "" : screen));
    }

    // --- /catalog trash ---------------------------------------------------------------------

    /** How many removals one page of the trash shows. */
    private static final int BIN = 10;

    /**
     * What has been removed and can still be restored.
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
            footer.append(button("Newer »", "/catalog trash --page " + (shown - 1), MUTED,
                    "Page " + (shown - 1))).append(Component.space());
        }

        if (shown < pages) {
            footer.append(button("« Older", "/catalog trash --page " + (shown + 1), MUTED,
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
                .append(button("Restore", restoreCommand(entry, ClickContext.TRASH), BRAND,
                        "Put " + entry.displayName() + " back"))
                .append(Component.space())
                .append(icon("×", DANGER, from("/catalog trash delete " + entry.storedAs(),
                                ClickContext.TRASH),
                        "Delete " + entry.displayName() + " permanently"))
                .build();
    }

    private static String restoreCommand(TrashEntry entry, String here) {
        return from("/catalog trash restore " + entry.storedAs(), here);
    }

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

    // --- /catalog history -------------------------------------------------------------------

    /** How many entries one page of the history shows. */
    private static final int EVENTS = 12;

    /**
     * What Catalog did, grouped by the day it happened.
     *
     * @param entries what to show, newest first
     * @param page    which page, from one
     * @param filter  what the list was narrowed to, shown in the header, or null when it was not
     * @param command the command that produced this list, which the pager adds a page to
     */
    public static List<Component> history(List<HistoryEntry> entries, int page, String filter,
                                          String command) {

        int pages = Math.max((entries.size() + EVENTS - 1) / EVENTS, 1);
        int shown = Math.min(Math.max(page, 1), pages);
        int first = (shown - 1) * EVENTS;

        List<Component> out = new ArrayList<>();

        TextComponent.Builder header = line()
                .append(Component.text("Catalog", BRAND).decorate(TextDecoration.BOLD))
                .append(Component.text("  history", MUTED));

        if (filter != null) {
            header.append(Component.text("  " + filter, TEXT));
        }

        out.add(header.build());

        if (entries.isEmpty()) {
            out.add(Component.empty());
            out.add(Component.text(INDENT + "Nothing recorded yet", MUTED));
            return out;
        }

        LocalDate day = null;

        for (HistoryEntry entry : entries.subList(first, Math.min(first + EVENTS, entries.size()))) {

            LocalDate on = LocalDate.ofInstant(entry.at(), ZoneId.systemDefault());

            if (!on.equals(day)) {
                day = on;
                out.add(Component.empty());
                out.add(Component.text(INDENT + dayName(on), BRAND));
            }

            out.add(historyRow(entry));
        }

        out.add(Component.empty());

        TextComponent.Builder footer = line()
                .append(Component.text(INDENT + "page ", MUTED))
                .append(Component.text(shown, TEXT))
                .append(Component.text(" of " + pages + "  ", MUTED));

        if (shown > 1) {
            footer.append(button("Newer", command + " --page " + (shown - 1), MUTED,
                    "Page " + (shown - 1))).append(Component.space());
        }

        if (shown < pages) {
            footer.append(button("Older", command + " --page " + (shown + 1), MUTED,
                    "Page " + (shown + 1))).append(Component.space());
        }

        footer.append(button("Plugins", "/catalog list", MUTED, "Back to the plugin list"));

        out.add(footer.build());

        return out;
    }

    private static String dayName(LocalDate on) {

        LocalDate today = LocalDate.now(ZoneId.systemDefault());

        if (on.equals(today)) {
            return "Today";
        }

        if (on.equals(today.minusDays(1))) {
            return "Yesterday";
        }

        return on.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH));
    }

    private static Component historyRow(HistoryEntry entry) {

        TextComponent.Builder row = line().append(Component.text(INDENT + INDENT));

        if (entry.name() != null) {
            row.append(Component.text(entry.name(), TEXT)).append(Component.text("  "));
        }

        row.append(Component.text(said(entry), colourOf(entry.event())))
                .hoverEvent(HoverEvent.showText(historyDetail(entry)));

        // A row narrows the list to its own plugin, which is how the filter is found without a
        // button for it anywhere.
        if (entry.slug() != null) {
            row.clickEvent(ClickEvent.runCommand("/catalog history --plugin " + entry.slug()));
        }

        return row.build();
    }

    /**
     * What an event reads as: red for something gone, amber for something owed, green for something
     * that landed.
     */
    private static TextColor colourOf(Event event) {

        return switch (event) {
            case INSTALLED, INSTALLED_AS_DEPENDENCY, RESTORED, UPDATES_APPLIED -> DONE;
            case UPDATE_STAGED, SWITCHED, ROLLED_BACK, UPDATE_HELD_BACK, REPLACED_BY_HAND -> PENDING;
            case TRASHED, DELETED, TRASH_EMPTIED, NO_LONGER_INSTALLED -> DANGER;
            case ADOPTED -> TEXT;
            default -> MUTED;
        };
    }

    /**
     * The sentence a row shows, which states the fact and leaves the rest to the hover.
     */
    private static String said(HistoryEntry entry) {

        return switch (entry.event()) {
            case INSTALLED -> "installed";
            case INSTALLED_AS_DEPENDENCY -> "installed as a dependency";
            case UPDATE_STAGED -> entry.byPerson() ? "update staged" : "auto-update staged";
            case UPDATE_CANCELLED -> "update cancelled";
            case SWITCHED -> "switched";
            case ROLLED_BACK -> "rolled back";
            case TRASHED -> "moved to trash";
            case RESTORED -> "restored";
            case DELETED -> "deleted from trash";
            case TRASH_EMPTIED -> "Trash emptied";
            case UPDATES_APPLIED -> entry.name() != null ? "applied on restart"
                    : "Applied " + entry.count() + " staged builds on restart";
            case ADOPTED -> entry.name() != null ? "adopted"
                    : "Adopted " + entry.count() + " plugins";
            case REPLACED_BY_HAND -> "replaced by hand";
            case NO_LONGER_INSTALLED -> "no longer installed";
            case UPDATE_HELD_BACK -> "update held back";
            case HELD -> "held";
            case UNHELD -> "unheld";
            case AUTO_UPDATE -> "auto-update " + entry.value();
            case CHANNEL -> "channel set to " + entry.value();
            case SOAK -> "soak set to " + ("default".equals(entry.value())
                    ? "default" : soakLabel(readInt(entry.value())));
        };
    }

    private static int readInt(String value) {

        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Component historyDetail(HistoryEntry entry) {

        TextComponent.Builder hover = Component.text()
                .append(Component.text(entry.name() == null ? "Catalog" : entry.name(), TEXT));

        if (entry.from() != null && entry.to() != null) {
            hover.append(Component.newline())
                    .append(Component.text(entry.from() + " -> " + entry.to(), MUTED));
        } else if (entry.to() != null) {
            hover.append(Component.newline()).append(Component.text(entry.to(), MUTED));
        }

        if (entry.channel() != null) {
            hover.append(Component.newline()).append(Component.text(entry.channel(), MUTED));
        }

        if (entry.event() == Event.INSTALLED_AS_DEPENDENCY && entry.value() != null) {
            hover.append(Component.newline())
                    .append(Component.text("required by " + entry.value(), MUTED));
        }

        if (entry.names() != null && !entry.names().isEmpty()) {
            hover.append(Component.newline())
                    .append(Component.text(String.join(", ", entry.names()), MUTED));
        }

        hover.append(Component.newline()).append(Component.newline());

        // The restart applied it, so there is nobody to name. Whoever queued it is on the row
        // that queued it.
        if (entry.event() != Event.UPDATES_APPLIED) {
            hover.append(Component.text(entry.byPerson() ? "by " + entry.by() : "by Catalog", TEXT))
                    .append(Component.newline());
        }

        hover.append(Component.text(when(entry.at()), MUTED));

        if (entry.slug() != null) {
            hover.append(Component.newline())
                    .append(Component.newline())
                    .append(Component.text("Show only " + entry.name(), TEXT))
                    .append(Component.newline())
                    .append(Component.text("/catalog history --plugin " + entry.slug(), MUTED));
        }

        return hover.build();
    }

    private static String when(Instant at) {
        return DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)
                .withZone(ZoneId.systemDefault()).format(at);
    }

    // --- confirmations and outcomes ---------------------------------------------------------

    /**
     * Asked before removing a plugin that other installed plugins require.
     *
     * @param plugin     what is being removed
     * @param dependents the installed plugins that name it
     * @param from       the screen the removal was asked from
     */
    public static List<Component> confirmRemove(TrackedPlugin plugin, List<TrackedPlugin> dependents,
                                                String from) {

        String key = key(plugin);
        List<Component> out = new ArrayList<>();

        out.add(line()
                .append(Component.text("Remove ", DANGER).decorate(TextDecoration.BOLD))
                .append(Component.text(plugin.displayName(), TEXT).decorate(TextDecoration.BOLD))
                .build());

        out.add(line()
                .append(Component.text(INDENT))
                .append(Component.text(dependents.size(), PENDING))
                .append(Component.text(dependents.size() == 1
                        ? " installed plugin requires it" : " installed plugins require it", MUTED))
                .build());

        List<String> names = new ArrayList<>();

        for (TrackedPlugin dependent : dependents) {
            names.add(dependent.displayName());
        }

        out.add(Component.text(INDENT + String.join(", ", names), TEXT));
        out.add(Component.text(INDENT + "They will not load after the next restart.", MUTED));

        out.add(Component.empty());

        out.add(line()
                .append(Component.text(INDENT))
                .append(button("Remove anyway", confirming("/catalog uninstall " + key, from),
                        DANGER, "Remove " + plugin.displayName()))
                .append(Component.space())
                .append(button("Cancel", backTo(from, "/catalog info " + key), MUTED, "Keep it"))
                .build());

        return out;
    }

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

    public static Component alreadyInstalled(TrackedPlugin plugin) {
        return line()
                .append(Component.text(plugin.displayName(), TEXT))
                .append(Component.text(" is already installed  ", MUTED))
                .append(button("Versions", "/catalog versions " + key(plugin), BRAND,
                        "Pick a different build to switch to"))
                .build();
    }

    public static Component installedWith(String name, String version, int dependencies) {
        return line()
                .append(Component.text(name + " " + version, TEXT))
                .append(Component.text(" and ", MUTED))
                .append(Component.text(dependencies, TEXT))
                .append(Component.text(dependencies == 1 ? " dependency installed, loads on restart"
                        : " dependencies installed, load on restart", MUTED))
                .build();
    }

    public static Component installedRequired(int count) {
        return line()
                .append(Component.text(count, DONE))
                .append(Component.text(count == 1 ? " dependency installed, loads on restart"
                        : " dependencies installed, load on restart", MUTED))
                .build();
    }

    public static Component installed(String name, String version) {
        return line()
                .append(Component.text(name + " " + version, TEXT))
                .append(Component.text(" installed, loads on restart", MUTED))
                .build();
    }

    public static Component removed(String name, boolean deleted, TrashEntry entry, String from) {

        TextComponent.Builder out = line()
                .append(Component.text(name, TEXT))
                .append(Component.text(deleted ? " moved to trash, unloads on restart"
                        : " moved to trash, file is removed on restart", MUTED));

        if (entry != null) {
            out.append(Component.space()).append(button("Undo", restoreCommand(entry, from), BRAND,
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
                .append(Component.text(on ? " automatically updates itself"
                        : " only updates manually", MUTED))
                .build();
    }

    public static Component soakSet(String name, int minutes, int defaultSoak) {

        boolean inherits = minutes == TrackedPlugin.INHERIT_SOAK;

        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(" waits ", MUTED))
                .append(Component.text(soakLabel(inherits ? defaultSoak : minutes), BRAND))
                .append(Component.text(" before updating itself", MUTED))
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

    public static Component stillHeld(String name) {
        return Component.text(name + " is held at its current version. Unhold it first.", DANGER);
    }

    public static Component noBuild(String title) {
        return Component.text(title + " has no build for this server", DANGER);
    }

    public static Component noVersion(String title, String named) {
        return Component.text("No build of " + title + " called " + named, DANGER);
    }

    public static Component nothingMissing() {
        return Component.text("Nothing required is missing", DANGER);
    }

    public static Component unreachable(String reason) {
        return Component.text("Could not reach Modrinth: " + reason, DANGER);
    }

    public static Component configFailed() {
        return Component.text("Could not read config.yml, check the console for errors.", DANGER);
    }

    public static Component badSoak() {
        return Component.text("Invalid soak time. Valid examples: 30m, 2h, 12h", DANGER);
    }

    public static Component stageFailed(String name, String reason) {
        return Component.text(name + ": " + reason, DANGER);
    }

    public static Component cancelled(String name) {
        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(" staged update dropped", MUTED))
                .build();
    }

    public static Component nothingStaged(String name) {
        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(" has no staged update", MUTED))
                .build();
    }

    public static Component needsDependencies(String name, int missing) {
        return line()
                .append(Component.text(name, TEXT))
                .append(Component.text(" needs " + missing + " plugin"
                        + (missing == 1 ? "" : "s") + " that are not installed", MUTED))
                .build();
    }

    public static Component failed(String reason) {
        return Component.text(reason, DANGER);
    }

    // --- utils ---------------------------------------------------------------------------

    private static Component buttons(List<Component> row) {

        TextComponent.Builder out = line().append(Component.text(INDENT));

        for (int i = 0; i < row.size(); i++) {

            if (i > 0) {
                out.append(Component.space());
            }

            out.append(row.get(i));
        }

        return out.build();
    }

    private static Component button(String label, String command, TextColor colour, String description) {

        boolean complete = !command.endsWith(" ");

        return Component.text("[", MUTED)
                .append(Component.text(label, colour))
                .append(Component.text("]", MUTED))
                .clickEvent(complete ? ClickEvent.runCommand(command) : ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(explain(description, command)))
                .insertion(ClickContext.strip(command).trim());
    }

    private static Component icon(String glyph, TextColor colour, String command, String description) {

        return Component.text("[", MUTED)
                .append(Component.text(glyph, colour))
                .append(Component.text("]", MUTED))
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(explain(description, command)))
                .insertion(ClickContext.strip(command).trim());
    }

    /**
     * Rewrites a screen for somewhere that cannot click.
     *
     * <p>Every widget carries the command it runs, so a console reads the command in place of a
     * button it has no way to press.</p>
     *
     * @param component the screen as a player would see it
     * @return the same screen with each button replaced by its command
     */
    public static Component typed(Component component) {

        String insertion = component.insertion();

        if (insertion != null && !insertion.isEmpty()) {
            return Component.text("[", MUTED)
                    .append(Component.text(insertion, labelColour(component)))
                    .append(Component.text("]", MUTED));
        }

        if (component.children().isEmpty()) {
            return component;
        }

        List<Component> children = new ArrayList<>();

        for (Component child : component.children()) {
            children.add(typed(child));
        }

        return component.children(children);
    }

    /**
     * The colour a widget reads as, which is on its label rather than its brackets.
     */
    private static TextColor labelColour(Component widget) {

        for (Component child : widget.children()) {
            if (child.color() != null && !MUTED.equals(child.color())) {
                return child.color();
            }
        }

        return widget.color() == null ? BRAND : widget.color();
    }

    private static Component explain(String description, String command) {

        return Component.text(description, TEXT)
                .append(Component.newline())
                .append(Component.text(ClickContext.strip(command).trim(), MUTED));
    }

    /**
     * Tags a command with the screen it is being offered from.
     */
    private static String from(String command, String screen) {
        return ClickContext.press(command, screen);
    }

    /**
     * Tags a command as the confirmation of one already asked about.
     */
    private static String confirming(String command, String screen) {
        return from(command, ClickContext.CONFIRM + (screen == null ? "" : screen));
    }

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
