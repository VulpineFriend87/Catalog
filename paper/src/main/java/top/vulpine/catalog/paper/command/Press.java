package top.vulpine.catalog.paper.command;

/**
 * Everything a button carries besides its command.
 *
 * @param screen    where the button was pressed, or null when it was not offered from a screen
 * @param confirmed whether this press is the answer to a confirmation
 * @param answer    {@link ClickContext#ALONE}, or null for the default of installing what is needed
 */
public record Press(String screen, boolean confirmed, String answer) {

    /**
     * @param screen where the button is being offered, or null
     * @return a plain press from that screen
     */
    public static Press on(String screen) {
        return new Press(screen, false, null);
    }

    /**
     * Reads what a button carried.
     *
     * @param data the payload {@link ClickContext#take} returned
     * @return what it carried, or null when the command was typed
     */
    public static Press of(String data) {

        if (data == null) {
            return null;
        }

        String rest = data;
        boolean confirmed = rest.startsWith(ClickContext.CONFIRM);

        if (confirmed) {
            rest = rest.substring(ClickContext.CONFIRM.length());
        }

        String answer = null;

        if (rest.startsWith(ClickContext.ALONE)) {
            answer = ClickContext.ALONE;
            rest = rest.substring(ClickContext.ALONE.length());
        }

        return new Press(rest.isEmpty() ? null : rest, confirmed, answer);
    }

    /**
     * @return this press, as the confirmation of the question it raised
     */
    public Press confirming() {
        return new Press(screen, true, answer);
    }

    /**
     * @param answer what was chosen on the confirmation screen
     * @return this press, carrying that answer
     */
    public Press answering(String answer) {
        return new Press(screen, confirmed, answer);
    }

    /**
     * @return the payload, in the form {@link #of} reads back
     */
    public String data() {
        return (confirmed ? ClickContext.CONFIRM : "")
                + (answer == null ? "" : answer)
                + (screen == null ? "" : screen);
    }

}
