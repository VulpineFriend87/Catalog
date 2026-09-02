package top.vulpine.catalog.paper.command;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.node.ExecutionContext;
import top.vulpine.catalog.paper.CatalogPaper;
import top.vulpine.catalog.tracking.model.TrackedPlugin;

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

    /**
     * What to offer for a plugin argument.
     *
     * <p>The display name when it is one word, the slug otherwise. A suggestion containing a space
     * cannot be completed in a single argument, so a name like "Axiom Paper Plugin" would only ever
     * fill in "Axiom" and then fail to resolve.</p>
     */
    private static List<String> names(Predicate<TrackedPlugin> filter) {

        CatalogPaper plugin = JavaPlugin.getPlugin(CatalogPaper.class);
        List<String> names = new ArrayList<>();

        for (TrackedPlugin tracked : plugin.getTracking().all()) {

            if (!filter.test(tracked)) {
                continue;
            }

            String name = tracked.displayName();

            if (name.indexOf(' ') >= 0 && tracked.slug() != null) {
                name = tracked.slug();
            }

            names.add(name);
        }

        return names;
    }

}
