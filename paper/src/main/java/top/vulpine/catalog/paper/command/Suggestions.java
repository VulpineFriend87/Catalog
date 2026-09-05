package top.vulpine.catalog.paper.command;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.node.ExecutionContext;
import top.vulpine.catalog.paper.CatalogPaper;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.trash.model.TrashEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

    private Suggestions() {
    }

    /** Every managed plugin. */
    public static final class Tracked implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            return names(plugin -> true);
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

            return names;
        }

    }

    /** Only the plugins that are not held yet. */
    public static final class Holdable implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            return names(plugin -> !plugin.isPinned());
        }

    }

    /** Only the plugins that are held. */
    public static final class Held implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            return names(TrackedPlugin::isPinned);
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

            return names;
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

            return names;
        }

    }

    /**
     * What to offer for a plugin argument: the name the plugin is listed under.
     *
     * <p>Names with a space are offered quoted, because Lamp reads a quoted argument as one value —
     * {@code readString} stops at the closing quote — so {@code "Axiom Paper Plugin"} arrives whole
     * and unquoted. Suggesting the slug instead would complete to something the list never showed
     * you.</p>
     *
     * <p>A name containing a quote of its own cannot be quoted this way, and falls back to the slug
     * rather than producing an argument that will not parse.</p>
     */
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
