package top.vulpine.catalog.install;

/**
 * Thrown when a file cannot be fetched, verified or put where it belongs.
 *
 * <p>The message is written to be shown to an operator as it is, because every one of these means
 * a plugin did not get installed and they are the ones who have to decide what to do about it.</p>
 */
public class InstallException extends RuntimeException {

    public InstallException(String message) {
        super(message);
    }

    public InstallException(String message, Throwable cause) {
        super(message, cause);
    }

}
