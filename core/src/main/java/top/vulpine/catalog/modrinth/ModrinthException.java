package top.vulpine.catalog.modrinth;

/**
 * Thrown when the Modrinth API cannot be reached, answers with an error status, or returns a body
 * that cannot be read.
 */
public class ModrinthException extends RuntimeException {

    private final int statusCode;

    public ModrinthException(String message) {
        this(message, 0, null);
    }

    public ModrinthException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public ModrinthException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * The HTTP status that caused this, or {@code 0} if the request never completed.
     *
     * @return the status code
     */
    public int statusCode() {
        return statusCode;
    }

}
