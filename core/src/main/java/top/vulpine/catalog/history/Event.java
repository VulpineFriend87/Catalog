package top.vulpine.catalog.history;

/**
 * What happened.
 */
public enum Event {

    INSTALLED,
    INSTALLED_AS_DEPENDENCY,
    UPDATE_STAGED,
    UPDATE_CANCELLED,
    SWITCHED,
    ROLLED_BACK,
    TRASHED,
    RESTORED,
    DELETED,
    TRASH_EMPTIED,
    UPDATES_APPLIED,

    ADOPTED,
    REPLACED_BY_HAND,
    NO_LONGER_INSTALLED,

    UPDATE_HELD_BACK,
    HELD,
    UNHELD,
    AUTO_UPDATE,
    CHANNEL,
    SOAK;

    /**
     * Whether this is a setting rather than something that changed what is installed.
     *
     * @return true for the rows the screen draws quieter
     */
    public boolean isSetting() {
        return this == HELD || this == UNHELD || this == AUTO_UPDATE
                || this == CHANNEL || this == SOAK;
    }

}
