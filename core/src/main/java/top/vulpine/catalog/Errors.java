package top.vulpine.catalog;

/**
 * Reading a cause out of a failure.
 */
public final class Errors {

    private Errors() {}

    /**
     * The message worth showing, since a failed future wraps the real cause.
     *
     * @param error what was thrown
     * @return the message, or the class name when there is none
     */
    public static String rootMessage(Throwable error) {

        Throwable cause = error;

        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }

        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

}
