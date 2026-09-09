package top.vulpine.catalog.jar.model;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.nio.file.Path;

/**
 * One jar found in the plugins folder, hashed and inspected.
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
     * <p>Size and modification time together are enough to skip re-hashing.</p>
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
