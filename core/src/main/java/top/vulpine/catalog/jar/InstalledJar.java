package top.vulpine.catalog.jar;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.nio.file.Path;

/**
 * One jar found in the plugins folder, hashed and inspected.
 *
 * <p>{@link #sha512()} is the identity Catalog cares about: it is what the Modrinth API is asked
 * about, and what tells us a file has been swapped out by hand.</p>
 */
@Getter
@Accessors(fluent = true)
public final class InstalledJar {

    private final Path path;
    private final String fileName;
    private final long size;
    private final long lastModified;
    private final String sha512;
    private final PluginDescriptor info;

    public InstalledJar(Path path, long size, long lastModified, String sha512, PluginDescriptor info) {
        this.path = path;
        this.fileName = path.getFileName().toString();
        this.size = size;
        this.lastModified = lastModified;
        this.sha512 = sha512;
        this.info = info;
    }

    /**
     * Whether this jar is unchanged since it was last scanned.
     *
     * <p>Size and modification time together are enough to skip re-hashing. A file edited in place
     * without either changing would be missed, but nothing writes jars that way, and the cost of
     * hashing every jar on every boot is real.</p>
     *
     * @param size         the size recorded previously
     * @param lastModified the modification time recorded previously
     * @return true if the file looks untouched
     */
    public boolean matches(long size, long lastModified) {
        return this.size == size && this.lastModified == lastModified;
    }

    @Override
    public String toString() {
        return "InstalledJar(" + fileName + ", " + (info.isPlugin() ? info.pluginName() : "no descriptor") + ")";
    }

}
