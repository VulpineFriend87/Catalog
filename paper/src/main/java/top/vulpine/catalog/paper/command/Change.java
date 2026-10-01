package top.vulpine.catalog.paper.command;

/**
 * What the confirmation screen is asking about.
 */
public enum Change {

    INSTALL("Install"),
    UPDATE("Update"),
    SWITCH("Switch"),
    ROLL_BACK("Roll back");

    private final String verb;

    Change(String verb) {
        this.verb = verb;
    }

    public String verb() {
        return verb;
    }

}
