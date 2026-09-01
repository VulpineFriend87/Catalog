package top.vulpine.catalog.tracking;

/**
 * Thrown when Catalog's own state files cannot be read or written.
 *
 * <p>Deliberately not swallowed anywhere in the core. Tracking state is what tells Catalog which
 * jars it is responsible for, and carrying on with a half-read view of that would risk acting on
 * the wrong file. The platform module decides how to surface it, which in practice means refusing
 * to enable rather than guessing.</p>
 */
public class TrackingException extends RuntimeException {

    public TrackingException(String message) {
        super(message);
    }

    public TrackingException(String message, Throwable cause) {
        super(message, cause);
    }

}
