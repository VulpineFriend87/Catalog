package top.vulpine.catalog.update.model;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * What this server is.
 *
 * <p>Built by the platform module.</p>
 */
@Getter
@Builder
@Accessors(fluent = true)
public final class ServerTarget {

    @NonNull
    private final ServerPlatform platform;

    /**
     * The exact Minecraft version.
     */
    @NonNull
    private final String gameVersion;

    /** The Java feature version this server runs on, for checking a downloaded jar against it. */
    private final int javaVersion;

    /**
     * The loaders to ask Modrinth for.
     *
     * @return this platform and everything it is a superset of
     */
    public List<String> loaders() {
        return platform.loaders();
    }

    /**
     * The game versions to accept, which is this one and nothing else.
     *
     * @return the acceptable versions
     */
    public List<String> gameVersions() {
        return List.of(gameVersion);
    }

    @Override
    public String toString() {
        return platform.id() + " " + gameVersion + " on Java " + javaVersion;
    }

}
