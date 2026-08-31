package top.vulpine.catalog.modrinth.http;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * An on-disk store of ETags and response bodies for GET requests.
 *
 * <p>Lets a repeated check cost a {@code 304 Not Modified} instead of a full body, which matters
 * for the version listings the GUI hits repeatedly. Purely an optimisation: every operation fails
 * quietly, and a cache miss is always safe.</p>
 */
public final class ResponseCache {

    private final Path directory;

    public ResponseCache(Path directory) {
        this.directory = directory;
    }

    /**
     * The cached response for a URL, if one was stored.
     *
     * @param url the request URL
     * @return the entry, or null if nothing usable is cached
     */
    public Entry lookup(String url) {

        Path file = fileFor(url);

        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int split = content.indexOf('\n');

            if (split < 0) {
                return null;
            }

            return new Entry(content.substring(0, split), content.substring(split + 1));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Stores a response body against the ETag the server returned with it.
     *
     * @param url  the request URL
     * @param etag the ETag header value
     * @param body the response body
     */
    public void store(String url, String etag, String body) {

        if (etag == null || etag.isEmpty()) {
            return;
        }

        Path file = fileFor(url);

        if (file == null) {
            return;
        }

        try {
            Files.createDirectories(directory);
            Files.writeString(file, etag + "\n" + body, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // A cache that cannot be written is not an error worth surfacing to the operator.
        }
    }

    /**
     * Removes every cached response.
     */
    public void clear() {

        if (!Files.isDirectory(directory)) {
            return;
        }

        try (var entries = Files.list(directory)) {
            entries.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private Path fileFor(String url) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String name = HexFormat.of().formatHex(digest.digest(url.getBytes(StandardCharsets.UTF_8)));
            return directory.resolve(name.substring(0, 32) + ".cache");
        } catch (Exception e) {
            return null;
        }
    }

    @Getter
    @Accessors(fluent = true)
    public static final class Entry {

        private final String etag;
        private final String body;

        private Entry(String etag, String body) {
            this.etag = etag;
            this.body = body;
        }

    }

}
