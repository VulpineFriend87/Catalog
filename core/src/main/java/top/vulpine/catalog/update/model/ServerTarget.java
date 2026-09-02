package top.vulpine.catalog.update.model;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.ArrayList;
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
     * The game versions to accept: this one, and the earlier patches of the same line.
     *
     * <p>Asking for the exact version alone is the honest question, and for a while it was the only
     * one Catalog asked. It does not survive contact with real metadata. Authors tick the versions
     * they had in mind when they uploaded and routinely miss the patch that shipped afterwards —
     * Axiom 6.0.0 lists 26.1 and 26.1.1 while its own predecessor lists 26.1.2 — so a server on the
     * newest patch is told the newest build does not exist.</p>
     *
     * <p>Only downwards, never upwards. A build made for 26.1.1 almost certainly runs on 26.1.2; a
     * build made for 26.1.5 has no business being offered to a server on 26.1.2. Whether the build
     * finally chosen actually named this exact version is reported rather than assumed, because a
     * patch release can still break a plugin and nobody should be told otherwise.</p>
     *
     * @return the acceptable versions, this one first
     */
    public List<String> gameVersions() {

        List<String> accepted = new ArrayList<>();
        accepted.add(gameVersion);

        int lastDot = gameVersion.lastIndexOf('.');

        if (lastDot <= 0) {
            return accepted;
        }

        String line = gameVersion.substring(0, lastDot);
        int patch;

        try {
            patch = Integer.parseInt(gameVersion.substring(lastDot + 1));
        } catch (NumberFormatException e) {
            // A snapshot or anything else unparseable: the exact version is all Catalog can claim.
            return accepted;
        }

        // The line itself is how Minecraft spells patch zero: 1.21, not 1.21.0.
        accepted.add(line);

        for (int earlier = 1; earlier < patch; earlier++) {
            accepted.add(line + "." + earlier);
        }

        return accepted;
    }

    @Override
    public String toString() {
        return platform.id() + " " + gameVersion + " on Java " + javaVersion;
    }

}
