package top.vulpine.catalog.common.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-512 hashing of files.
 *
 * <p>SHA-512 rather than SHA-1 because it is what Catalog sends to Modrinth, and using one algorithm
 * end to end means a hash read from the API and a hash computed from disk are directly comparable
 * with no conversion step to get wrong.</p>
 */
public final class Hashing {

    private static final int BUFFER_SIZE = 64 * 1024;

    private Hashing() {
    }

    /**
     * Hashes a file.
     *
     * @param file the file to read
     * @return the lowercase hex SHA-512
     * @throws IOException if the file cannot be read
     */
    public static String sha512(Path file) throws IOException {

        MessageDigest digest = digest();

        try (InputStream in = Files.newInputStream(file)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int read;

            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {

        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            // Required of every JVM by the platform specification.
            throw new IllegalStateException("SHA-512 is unavailable on this JVM", e);
        }
    }

}
