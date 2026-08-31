package top.vulpine.catalog.modrinth.model;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * A single file attached to a Modrinth version.
 *
 * <p>A version usually carries one {@link #primary() primary} file, the plugin jar itself, and may
 * carry extra ones — Typewriter ships its extensions this way. Catalog never installs a
 * non-primary file without being told where it belongs.</p>
 *
 * <p>Bound by Gson through field names, so the fields are written to reflectively and there is no
 * constructor.</p>
 */
@Getter
@Accessors(fluent = true)
public final class VersionFile {

    private Hashes hashes;
    private String url;
    private String filename;
    private boolean primary;
    private long size;
    private String fileType;

    /**
     * The SHA-512 of this file, which is the identity Catalog indexes jars by.
     *
     * @return the hash, or null if Modrinth did not report one
     */
    public String sha512() {
        return hashes == null ? null : hashes.sha512;
    }

    /**
     * The SHA-1 of this file.
     *
     * @return the hash, or null if Modrinth did not report one
     */
    public String sha1() {
        return hashes == null ? null : hashes.sha1;
    }

    @Getter
    @Accessors(fluent = true)
    public static final class Hashes {
        private String sha1;
        private String sha512;
    }

}
