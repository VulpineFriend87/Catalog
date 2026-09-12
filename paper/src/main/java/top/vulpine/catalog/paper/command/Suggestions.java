package top.vulpine.catalog.paper.command;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.node.ExecutionContext;
import revxrsal.commands.stream.StringStream;
import top.vulpine.catalog.paper.CatalogPaper;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.trash.model.TrashEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tab completion for the arguments that name a plugin Catalog already manages.
 *
 * <p>Each one offers only the plugins the command could actually act on, so completing an argument
 * can never produce a command that is refused.</p>
 *
 * <p>Lamp builds these itself, so they take no constructor and reach the plugin through Bukkit.</p>
 */
public final class Suggestions {

    private Suggestions() {}

    /** Every plugin the history mentions, installed or not. */
    public static final class Logged implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            return matching(context, JavaPlugin.getPlugin(CatalogPaper.class).loggedPlugins());
        }

    }

    /** Every managed plugin. */
    public static final class Tracked implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            return matching(context, names(plugin -> true));
        }

    }

    /** Only the plugins with an update waiting, plus the word that means all of them. */
    public static final class Updatable implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {

            Set<String> waiting = JavaPlugin.getPlugin(CatalogPaper.class).updatesByProject().keySet();
            List<String> names = names(plugin -> waiting.contains(plugin.projectId()));

            if (!names.isEmpty()) {
                names.add(0, "all");
            }

            return matching(context, names);
        }

    }

    /** Only the plugins that are not held yet. */
    public static final class Holdable implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            return matching(context, names(plugin -> !plugin.isPinned()));
        }

    }

    /** Only the plugins that are held. */
    public static final class Held implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            return matching(context, names(TrackedPlugin::isPinned));
        }

    }

    /** Only what is in the trash, by the name it was removed under. */
    public static final class Trashed implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {

            List<String> names = new ArrayList<>();

            for (TrashEntry entry : JavaPlugin.getPlugin(CatalogPaper.class).trashed()) {
                names.add(quoted(entry.displayName()));
            }

            return matching(context, names);
        }

    }

    /** What is in the trash, plus the word that means every one of them. */
    public static final class Discardable implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {

            List<String> names = new ArrayList<>();

            for (TrashEntry entry : JavaPlugin.getPlugin(CatalogPaper.class).trashed()) {
                names.add(quoted(entry.displayName()));
            }

            if (!names.isEmpty()) {
                names.add(0, "all");
            }

            return matching(context, names);
        }

    }

    /**
     * What to offer for a plugin argument: the name the plugin is listed under.
     */
    /**
     * Narrows a list of completions to what has been typed so far.
     *
     * <p>Brigadier filters literals on the client, but anything the server supplies is sent as it
     * is: Lamp hands every value straight to the builder without looking at the partial input, so
     * without this the list never shrinks as you type.</p>
     */
    private static Collection<String> matching(ExecutionContext<BukkitCommandActor> context,
                                               List<String> names) {

        StringStream input = context.input();
        return matching(input.hasRemaining() ? input.peekRemaining() : "", names);
    }

    /**
     * @param typed what has been written for this argument, quoted or not
     * @param names every completion the command could offer
     * @return the ones that start with what was typed
     */
    static List<String> matching(String typed, List<String> names) {

        String wanted = unquoted(typed).toLowerCase(Locale.ROOT);

        if (wanted.isEmpty()) {
            return names;
        }

        List<String> matched = new ArrayList<>();

        for (String name : names) {
            if (unquoted(name).toLowerCase(Locale.ROOT).startsWith(wanted)) {
                matched.add(name);
            }
        }

        return matched;
    }

    /**
     * Drops the opening quote a name with a space is offered under, so a typed quote still matches.
     */
    private static String unquoted(String value) {

        String trimmed = value.trim();
        return trimmed.startsWith("\"") ? trimmed.substring(1) : trimmed;
    }

    private static List<String> names(Predicate<TrackedPlugin> filter) {

        CatalogPaper plugin = JavaPlugin.getPlugin(CatalogPaper.class);
        List<String> names = new ArrayList<>();

        for (TrackedPlugin tracked : plugin.getTracking().all()) {

            if (filter.test(tracked)) {
                names.add(suggestionFor(tracked));
            }
        }

        return names;
    }

    private static String suggestionFor(TrackedPlugin tracked) {

        String name = tracked.displayName();

        if (name.indexOf(' ') >= 0 && name.indexOf('"') >= 0) {
            return tracked.slug() != null ? tracked.slug() : name;
        }

        return quoted(name);
    }

    private static String quoted(String name) {

        if (name.indexOf(' ') < 0 || name.indexOf('"') >= 0) {
            return name;
        }

        return '"' + name + '"';
    }

}
