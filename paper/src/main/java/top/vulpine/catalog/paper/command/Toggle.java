package top.vulpine.catalog.paper.command;

/**
 * On or off, as an argument.
 *
 * <p>An enum rather than a boolean so the command completes to {@code on} and {@code off} instead of
 * {@code true} and {@code false}, which is not how anyone says it.</p>
 */
public enum Toggle {

    ON,
    OFF;

    /**
     * @return true when this is {@link #ON}
     */
    public boolean on() {
        return this == ON;
    }

}
