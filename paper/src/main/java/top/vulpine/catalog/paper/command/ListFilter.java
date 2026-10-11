package top.vulpine.catalog.paper.command;

import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.update.model.UpdateCandidate;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * What {@code /catalog list} can be narrowed to, named after the label each row already shows.
 */
public enum ListFilter {

    UPDATES,
    RESTART,
    HELD,
    UNTRACKED;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param plugin  a managed plugin
     * @param updates what is available, keyed by project id
     * @return whether this filter keeps it
     */
    public boolean keeps(TrackedPlugin plugin, Map<String, UpdateCandidate> updates) {
        return switch (this) {
            case UPDATES -> updates.containsKey(plugin.projectId()) && !plugin.pendingLoad();
            case RESTART -> plugin.awaitingRestart();
            case HELD -> plugin.isPinned();
            case UNTRACKED -> false;
        };
    }

    /**
     * Several filters keep whatever any one of them keeps, and none keeps everything.
     */
    public static boolean keeps(Set<ListFilter> filters, TrackedPlugin plugin,
                                Map<String, UpdateCandidate> updates) {

        if (filters.isEmpty()) {
            return true;
        }

        for (ListFilter filter : filters) {
            if (filter.keeps(plugin, updates)) {
                return true;
            }
        }

        return false;
    }

    public static boolean keepsUntracked(Set<ListFilter> filters) {
        return filters.isEmpty() || filters.contains(UNTRACKED);
    }

    /**
     * @return the switches that reproduce these filters, each with a leading space
     */
    public static String switches(Set<ListFilter> filters) {

        StringBuilder out = new StringBuilder();

        for (ListFilter filter : filters) {
            out.append(" --").append(filter.label());
        }

        return out.toString();
    }

    /**
     * The screen token for a filtered list, which a button carries so the list it redraws keeps
     * the same filters.
     */
    public static String screen(Set<ListFilter> filters) {

        if (filters.isEmpty()) {
            return ClickContext.LIST;
        }

        StringJoiner joined = new StringJoiner(",", ClickContext.LIST + ":", "");

        for (ListFilter filter : filters) {
            joined.add(filter.label());
        }

        return joined.toString();
    }

    /**
     * @param screen a token from {@link #screen}
     * @return the filters it carries, or null when it is not a list at all
     */
    public static Set<ListFilter> parse(String screen) {

        if (screen == null) {
            return null;
        }

        if (screen.equals(ClickContext.LIST)) {
            return EnumSet.noneOf(ListFilter.class);
        }

        if (!screen.startsWith(ClickContext.LIST + ":")) {
            return null;
        }

        Set<ListFilter> filters = EnumSet.noneOf(ListFilter.class);

        for (String label : screen.substring(ClickContext.LIST.length() + 1).split(",")) {
            for (ListFilter filter : values()) {
                if (filter.label().equals(label)) {
                    filters.add(filter);
                }
            }
        }

        return filters;
    }

}
