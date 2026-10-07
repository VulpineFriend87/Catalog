package top.vulpine.catalog.paper.command;

import top.vulpine.catalog.history.Event;

import java.util.Locale;

/**
 * What {@code /catalog history --event} can be narrowed to.
 */
public enum HistoryKind {

    INSTALL,
    UPDATE,
    FAILED,
    TRASH,
    TRACKING,
    SETTINGS;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean covers(Event event) {

        // The only kind that overlaps another.
        if (this == FAILED) {
            return event == Event.UPDATE_HELD_BACK || event == Event.AUTO_UPDATE_FAILED
                    || event == Event.UPDATE_NOT_APPLIED || event == Event.UPDATE_LOST
                    || event == Event.UPDATE_NOT_ENABLED || event == Event.INSTALL_NOT_ENABLED;
        }

        if (event.isSetting()) {
            return this == SETTINGS;
        }

        return switch (event) {
            case INSTALLED, INSTALLED_AS_DEPENDENCY, INSTALL_NOT_ENABLED -> this == INSTALL;
            case UPDATE_STAGED, UPDATE_CANCELLED, SWITCHED, ROLLED_BACK, UPDATES_APPLIED,
                 UPDATE_HELD_BACK, AUTO_UPDATE_FAILED, UPDATE_NOT_APPLIED,
                 UPDATE_LOST, UPDATE_NOT_ENABLED -> this == UPDATE;
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
