package top.vulpine.catalog.common.index;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads what a plugin jar declares about itself, without loading a single class from it.
 *
 * <p>Handles all three descriptors Catalog may meet: {@code plugin.yml}, {@code paper-plugin.yml}
 * and {@code velocity-plugin.json}.</p>
 */
public final class JarInspector {

    /**
     * How many class files to sample when the main class is unknown.
     *
     * <p>Reading eight bytes is cheap, opening a stream per entry is not, and a shaded jar can hold
     * thousands of classes. The sample only has to be large enough to catch a jar compiled for a
     * newer Java than the server runs.</p>
     */
    private static final int CLASS_SAMPLE_LIMIT = 50;

    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

    private JarInspector() {
    }

    /**
     * Inspects a jar.
     *
     * <p>Never throws for a malformed or unreadable jar: an unreadable file simply yields
     * {@link JarInfo#unknown()}, because a jar Catalog cannot describe is still a jar it must leave
     * alone rather than fail over.</p>
     *
     * @param jar the file to inspect
     * @return what the jar declares
     */
    public static JarInfo inspect(Path jar) {

        try (ZipFile zip = new ZipFile(jar.toFile())) {

            Declared declared = readDescriptor(zip);
            int major = readBytecodeMajor(zip, declared.mainClass);

            return new JarInfo(declared.name, declared.version, declared.mainClass,
                    declared.apiVersion, declared.descriptor, major);

        } catch (IOException | RuntimeException e) {
            return JarInfo.unknown();
        }
    }

    // --- Descriptors -----------------------------------------------------------------------------

    private static Declared readDescriptor(ZipFile zip) {

        ZipEntry bukkit = zip.getEntry("plugin.yml");

        if (bukkit != null) {
            Declared declared = readYamlDescriptor(zip, bukkit, JarInfo.Descriptor.BUKKIT);

            if (declared != null) {
                return declared;
            }
        }

        ZipEntry paper = zip.getEntry("paper-plugin.yml");

        if (paper != null) {
            Declared declared = readYamlDescriptor(zip, paper, JarInfo.Descriptor.PAPER);

            if (declared != null) {
                return declared;
            }
        }

        ZipEntry velocity = zip.getEntry("velocity-plugin.json");

        if (velocity != null) {
            Declared declared = readVelocityDescriptor(zip, velocity);

            if (declared != null) {
                return declared;
            }
        }

        return Declared.empty();
    }

    /**
     * Pulls the handful of top-level scalars Catalog needs out of a plugin descriptor.
     *
     * <p>Deliberately not a YAML parser. SnakeYAML crosses a major version boundary across the Paper
     * releases Catalog supports — 1.18.2 ships 1.30 while modern Paper ships 2.x — and binding the
     * jar index to that would inherit the incompatibility on every server. The four keys needed here
     * are always flat scalars at column zero, so they can be read directly, and anything this reader
     * does not understand is reported as absent rather than guessed at.</p>
     */
    private static Declared readYamlDescriptor(ZipFile zip, ZipEntry entry, JarInfo.Descriptor descriptor) {

        Map<String, String> values = new HashMap<>();

        try (InputStream in = zip.getInputStream(entry);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {

                // Only column-zero keys are top level; anything indented belongs to a nested block.
                if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
                    continue;
                }

                if (line.startsWith("#") || line.startsWith("-") || line.startsWith("---")) {
                    continue;
                }

                int colon = line.indexOf(':');

                if (colon <= 0) {
                    continue;
                }

                String key = line.substring(0, colon).trim();
                String value = unquote(stripComment(line.substring(colon + 1).trim()));

                if (!value.isEmpty()) {
                    values.put(key, value);
                }
            }

        } catch (IOException e) {
            return null;
        }

        String name = values.get("name");

        if (name == null) {
            return null;
        }

        return new Declared(name, values.get("version"), values.get("main"),
                values.get("api-version"), descriptor);
    }

    private static Declared readVelocityDescriptor(ZipFile zip, ZipEntry entry) {

        try (InputStream in = zip.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {

            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

            // The id is the identity Velocity enforces uniqueness on; name is only a display label.
            String name = string(json, "id");

            if (name == null) {
                name = string(json, "name");
            }

            if (name == null) {
                return null;
            }

            return new Declared(name, string(json, "version"), string(json, "main"),
                    null, JarInfo.Descriptor.VELOCITY);

        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject json, String key) {

        if (!json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }

        String value = json.get(key).getAsString();
        return value.isBlank() ? null : value;
    }

    // --- Bytecode --------------------------------------------------------------------------------

    /**
     * Finds the highest class file version in the jar, which is the lowest Java the server must run.
     */
    private static int readBytecodeMajor(ZipFile zip, String mainClass) {

        if (mainClass != null) {

            ZipEntry main = zip.getEntry(mainClass.replace('.', '/') + ".class");

            if (main != null) {
                int major = majorVersionOf(zip, main);

                if (major > 0) {
                    return major;
                }
            }
        }

        int highest = 0;
        int sampled = 0;

        Enumeration<? extends ZipEntry> entries = zip.entries();

        while (entries.hasMoreElements() && sampled < CLASS_SAMPLE_LIMIT) {

            ZipEntry entry = entries.nextElement();
            String name = entry.getName();

            if (!name.endsWith(".class") || name.endsWith("module-info.class")) {
                continue;
            }

            // Multi-release entries only load on JVMs new enough to ask for them, so a high class
            // version under here says nothing about whether the jar runs on this server.
            if (name.startsWith("META-INF/versions/")) {
                continue;
            }

            highest = Math.max(highest, majorVersionOf(zip, entry));
            sampled++;
        }

        return highest;
    }

    private static int majorVersionOf(ZipFile zip, ZipEntry entry) {

        try (DataInputStream in = new DataInputStream(zip.getInputStream(entry))) {

            if (in.readInt() != CLASS_FILE_MAGIC) {
                return 0;
            }

            in.readUnsignedShort();
            return in.readUnsignedShort();

        } catch (IOException e) {
            return 0;
        }
    }

    // --- Text helpers ----------------------------------------------------------------------------

    /**
     * Drops a trailing comment, but only when the hash is clearly separated, so that a value such as
     * {@code prefix: "#1 server"} survives.
     */
    private static String stripComment(String value) {

        if (value.startsWith("\"") || value.startsWith("'")) {
            return value;
        }

        int hash = value.indexOf(" #");
        return hash < 0 ? value : value.substring(0, hash).trim();
    }

    private static String unquote(String value) {

        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }

        return value;
    }

    private static final class Declared {

        private final String name;
        private final String version;
        private final String mainClass;
        private final String apiVersion;
        private final JarInfo.Descriptor descriptor;

        private Declared(String name, String version, String mainClass, String apiVersion,
                         JarInfo.Descriptor descriptor) {
            this.name = name;
            this.version = version;
            this.mainClass = mainClass;
            this.apiVersion = apiVersion;
            this.descriptor = descriptor;
        }

        private static Declared empty() {
            return new Declared(null, null, null, null, JarInfo.Descriptor.NONE);
        }

    }

}
