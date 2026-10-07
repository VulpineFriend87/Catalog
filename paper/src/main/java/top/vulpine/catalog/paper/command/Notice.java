package top.vulpine.catalog.paper.command;

import java.util.List;

/**
 * One fact the join message can state, with the plugins it is about.
 */
public record Notice(Kind kind, List<Item> plugins) {

    /**
     * @param name         what the plugin is called, shown on the line
     * @param detail       the builds involved, shown on hover, or null
     * @param reason       why it did not happen, shown on hover, or null
     * @param rollback     the command that rolls it back, or null when there is nothing to return to
     * @param rollbackTo   the build a rollback returns to
     * @param rollbackGone whether that build was removed from Modrinth
     */
    public record Item(String name, String detail, String reason, String rollback, String rollbackTo,
                       boolean rollbackGone) {

        public Item(String name, String detail, String reason) {
            this(name, detail, reason, null, null, false);
        }

        public Item(String name, String detail) {
            this(name, detail, null);
        }

    }

    /**
     * In order of urgency.
     */
    public enum Kind {
        DID_NOT_ENABLE,
        DID_NOT_APPLY,
        AUTO_UPDATE_FAILED,
        APPLIED,
        WAITING,
        AVAILABLE
    }

}
