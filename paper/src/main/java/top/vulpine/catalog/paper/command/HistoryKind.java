package top.vulpine.catalog.paper.command;

import top.vulpine.catalog.history.Event;

import java.util.Locale;

/**
 * What {@code /catalog history --event} can be narrowed to.
 *
 * <p>Grouped, because the history records twenty-odd events and nobody looking for "what got
 * updated" should need to know that a switch and a rollback are separate ones.</p>
 */
public enum HistoryKind {

    INSTALL,
    UPDATE,
    TRASH,
    TRACKING,
    SETTINGS;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean covers(Event event) {

        if (event.isSetting()) {
            return this == SETTINGS;
        }

        return switch (event) {
            case INSTALLED, INSTALLED_AS_DEPENDENCY -> this == INSTALL;
            case UPDATE_STAGED, UPDATE_CANCELLED, SWITCHED, ROLLED_BACK, UPDATES_APPLIED,
                 UPDATE_HELD_BACK -> this == UPDATE;
            case TRASHED, RESTORED, DELETED, TRASH_EMPTIED -> this == TRASH;
            case ADOPTED, UNTRACKED, REPLACED_BY_HAND, NO_LONGER_INSTALLED -> this == TRACKING;
            default -> false;
        };
    }

    /**
     * @param typed what was given to the flag
     * @return the kind, or null if nothing is called that
     */
    public static HistoryKind named(String typed) {

        for (HistoryKind kind : values()) {
            if (kind.label().equalsIgnoreCase(typed.trim())) {
                return kind;
            }
        }

        return null;
    }

}
