package top.vulpine.catalog.update.model;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * What this server is, expressed as the questions Modrinth needs answered.
 *
 * <p>Built by the platform module, which is the only part that knows how to ask Bukkit or Velocity
 * what it is running. The core only needs the answers.</p>
 */
@Getter
@Builder
@Accessors(fluent = true)
public final class ServerTarget {

    @NonNull
    private final ServerPlatform platform;

    /**
     * The exact Minecraft version, as Modrinth spells it: {@code 1.21.4}, {@code 26.2}.
     *
     * <p>Exactness matters more than it looks. A project can publish one release as a dozen Modrinth
     * versions, each pinned to a handful of game versions — asking with {@code 1.21.4} returns the
     * build for 1.21.4 and not the newest one overall, which is the difference between an update
     * that loads and one that does not.</p>
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
     * <p>Catalog briefly accepted earlier patches of the same line, to work around authors who
     * publish for 26.1 and forget to tick 26.1.2. That is the author's oversight to fix, and
     * guessing on their behalf trades a guarantee — every build offered will load — for a warning
     * nobody reads. Compatibility stays exactly what was declared.</p>
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
