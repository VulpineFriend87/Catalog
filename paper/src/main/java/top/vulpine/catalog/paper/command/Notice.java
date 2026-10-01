package top.vulpine.catalog.paper.command;

import java.util.List;

/**
 * One fact the join message can state, with the plugins it is about.
 */
public record Notice(Kind kind, List<Item> plugins) {

    /**
     * @param name   what the plugin is called, shown on the line
     * @param detail the builds involved, shown on hover, or null
     * @param reason why it did not happen, shown on hover, or null
     */
    public record Item(String name, String detail, String reason) {

        public Item(String name, String detail) {
            this(name, detail, null);
        }

    }

    /**
     * In order of urgency.
     */
    public enum Kind {
        DID_NOT_APPLY,
        AUTO_UPDATE_FAILED,
        APPLIED,
        WAITING,
        AVAILABLE
    }

}
