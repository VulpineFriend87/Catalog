package top.vulpine.catalog.tracking;

/**
 * Thrown when Catalog's own state files cannot be read or written.
 */
public class TrackingException extends RuntimeException {

    public TrackingException(String message) {
        super(message);
    }

    public TrackingException(String message, Throwable cause) {
        super(message, cause);
    }

}
