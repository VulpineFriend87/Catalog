package top.vulpine.catalog.jar.model;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * What a plugin jar says about itself, read from its own descriptor file.
 *
 * <p>None of this decides what a jar <em>is</em> — that is settled by hash against the Modrinth
 * API. It answers the questions a hash cannot: which plugin a file belongs to, whether two jars
 * claim the same plugin, and whether a jar was compiled for a newer Java than the server runs.</p>
 *
 * <p>Every field may be absent. A jar with no readable descriptor is not an error; it is just a jar
 * Catalog knows less about.</p>
 */
@Getter
@Builder
@Accessors(fluent = true)
public final class PluginDescriptor {

    private final String pluginName;
    private final String pluginVersion;
    private final String mainClass;
    private final String apiVersion;

    @Builder.Default
    private final Kind kind = Kind.NONE;

    private final int bytecodeMajor;

    /**
     * An empty descriptor, for a jar that could not be read.
     *
     * @return a descriptor with everything absent
     */
    public static PluginDescriptor unknown() {
        return PluginDescriptor.builder().build();
    }

    /**
     * The Java feature version this jar was compiled for.
     *
     * <p>Class file major versions start at 45 for Java 1.1, so 61 means Java 17 and 65 means
     * Java 21.</p>
     *
     * @return the Java version, or 0 if no class file could be read
     */
    public int javaVersion() {
        return bytecodeMajor == 0 ? 0 : bytecodeMajor - 44;
    }

    /**
     * Whether this jar declares a plugin Catalog can reason about at all.
     *
     * @return true if a plugin name was found
     */
    public boolean isPlugin() {
        return pluginName != null && !pluginName.isBlank();
    }

    /**
     * Which descriptor file a jar was described by, which also says what platform it targets.
     */
    public enum Kind {

        /** {@code plugin.yml} — a Bukkit-style plugin. */
        BUKKIT,

        /** {@code paper-plugin.yml} — a plugin using Paper's own loader. */
        PAPER,

        /** {@code velocity-plugin.json} — a proxy plugin. */
        VELOCITY,

        /** No recognised descriptor; possibly a library rather than a plugin. */
        NONE

    }

}
