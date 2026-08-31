package top.vulpine.catalog.common.index;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * What a plugin jar says about itself, read from its own descriptor.
 *
 * <p>None of this is used to decide what a jar <em>is</em> — that is settled by hash against the
 * Modrinth API. It is used for the things a hash cannot answer: telling the operator which plugin a
 * file belongs to, spotting two jars that declare the same plugin, and refusing a jar compiled for
 * a newer Java than the server runs.</p>
 *
 * <p>Every field may be null or zero. A jar with no readable descriptor is not an error; it is just
 * a jar Catalog knows less about.</p>
 */
@Getter
@Accessors(fluent = true)
public final class JarInfo {

    private final String pluginName;
    private final String pluginVersion;
    private final String mainClass;
    private final String apiVersion;
    private final Descriptor descriptor;
    private final int bytecodeMajor;

    JarInfo(String pluginName, String pluginVersion, String mainClass, String apiVersion,
            Descriptor descriptor, int bytecodeMajor) {
        this.pluginName = pluginName;
        this.pluginVersion = pluginVersion;
        this.mainClass = mainClass;
        this.apiVersion = apiVersion;
        this.descriptor = descriptor;
        this.bytecodeMajor = bytecodeMajor;
    }

    /**
     * An empty result, for a jar whose descriptor could not be read.
     *
     * @return an info with everything absent
     */
    public static JarInfo unknown() {
        return new JarInfo(null, null, null, null, Descriptor.NONE, 0);
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
     * Which descriptor a jar was described by, which also tells us what platform it targets.
     */
    public enum Descriptor {

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
