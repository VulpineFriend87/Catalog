package top.vulpine.catalog.common.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JarInspector")
class JarInspectorTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("reads the scalars Catalog needs out of plugin.yml")
    void readsBukkitDescriptor() throws IOException {

        Path jar = TestJars.builder(directory, "plugin.jar")
                .descriptor("plugin.yml", """
                        name: LuckPerms
                        version: 5.4.102
                        main: me.lucko.luckperms.bukkit.LPBukkitBootstrap
                        api-version: "1.18"
                        """)
                .build();

        JarInfo info = JarInspector.inspect(jar);

        assertAll(
                () -> assertEquals("LuckPerms", info.pluginName()),
                () -> assertEquals("5.4.102", info.pluginVersion()),
                () -> assertEquals("me.lucko.luckperms.bukkit.LPBukkitBootstrap", info.mainClass()),
                () -> assertEquals("1.18", info.apiVersion(), "quotes are stripped"),
                () -> assertEquals(JarInfo.Descriptor.BUKKIT, info.descriptor()),
                () -> assertTrue(info.isPlugin())
        );
    }

    @Test
    @DisplayName("ignores nested keys, list items and comments")
    void ignoresEverythingThatIsNotATopLevelScalar() throws IOException {

        Path jar = TestJars.builder(directory, "nested.jar")
                .descriptor("plugin.yml", """
                        # a leading comment
                        name: Example
                        version: 1.0
                        commands:
                          example:
                            description: not the plugin description
                            aliases:
                              - ex
                        authors:
                          - Someone
                        """)
                .build();

        JarInfo info = JarInspector.inspect(jar);

        assertAll(
                () -> assertEquals("Example", info.pluginName()),
                () -> assertEquals("1.0", info.pluginVersion()),
                () -> assertNull(info.mainClass(), "no main was declared at the top level")
        );
    }

    @Test
    @DisplayName("strips a trailing comment but keeps a hash inside a quoted value")
    void handlesHashes() throws IOException {

        Path jar = TestJars.builder(directory, "hashes.jar")
                .descriptor("plugin.yml", """
                        name: Example # the plugin name
                        version: "#1"
                        """)
                .build();

        JarInfo info = JarInspector.inspect(jar);

        assertEquals("Example", info.pluginName(), "trailing comment dropped");
        assertEquals("#1", info.pluginVersion(), "a quoted hash is part of the value");
    }

    @Test
    @DisplayName("falls back to paper-plugin.yml")
    void readsPaperDescriptor() throws IOException {

        Path jar = TestJars.builder(directory, "paper.jar")
                .descriptor("paper-plugin.yml", """
                        name: ModernPlugin
                        version: 2.0
                        main: com.example.Modern
                        api-version: "1.21"
                        """)
                .build();

        JarInfo info = JarInspector.inspect(jar);

        assertEquals("ModernPlugin", info.pluginName());
        assertEquals(JarInfo.Descriptor.PAPER, info.descriptor());
    }

    @Test
    @DisplayName("reads velocity-plugin.json and prefers the id over the display name")
    void readsVelocityDescriptor() throws IOException {

        Path jar = TestJars.builder(directory, "proxy.jar")
                .descriptor("velocity-plugin.json", """
                        {
                          "id": "catalog",
                          "name": "Catalog",
                          "version": "1.0",
                          "main": "top.vulpine.catalog.velocity.CatalogVelocity"
                        }
                        """)
                .build();

        JarInfo info = JarInspector.inspect(jar);

        assertAll(
                () -> assertEquals("catalog", info.pluginName(), "the id is what Velocity enforces"),
                () -> assertEquals("1.0", info.pluginVersion()),
                () -> assertEquals(JarInfo.Descriptor.VELOCITY, info.descriptor())
        );
    }

    @Test
    @DisplayName("a jar with no descriptor is unknown rather than an error")
    void toleratesNonPlugins() throws IOException {

        Path jar = TestJars.builder(directory, "library.jar")
                .classFile("com/example/Util.class", 61)
                .build();

        JarInfo info = JarInspector.inspect(jar);

        assertFalse(info.isPlugin());
        assertEquals(JarInfo.Descriptor.NONE, info.descriptor());
    }

    @Test
    @DisplayName("a file that is not a jar at all is unknown rather than an error")
    void toleratesGarbage() throws IOException {

        Path notAJar = directory.resolve("broken.jar");
        java.nio.file.Files.writeString(notAJar, "this is not a zip archive");

        JarInfo info = JarInspector.inspect(notAJar);

        assertFalse(info.isPlugin());
        assertEquals(0, info.javaVersion());
    }

    @Test
    @DisplayName("reports the Java version the main class was compiled for")
    void readsBytecodeVersionOfMainClass() throws IOException {

        Path jar = TestJars.builder(directory, "java21.jar")
                .descriptor("plugin.yml", """
                        name: Modern
                        main: com.example.Main
                        """)
                .classFile("com/example/Main.class", 65)
                .build();

        JarInfo info = JarInspector.inspect(jar);

        assertEquals(65, info.bytecodeMajor());
        assertEquals(21, info.javaVersion(), "class major 65 is Java 21");
    }

    @Test
    @DisplayName("ignores multi-release entries, which do not load on older JVMs")
    void ignoresMultiReleaseClasses() throws IOException {

        Path jar = TestJars.builder(directory, "multirelease.jar")
                .classFile("com/example/Util.class", 61)
                .classFile("META-INF/versions/21/com/example/Util.class", 65)
                .build();

        JarInfo info = JarInspector.inspect(jar);

        assertEquals(17, info.javaVersion(), "the base class decides, not the versioned override");
    }

    @Test
    @DisplayName("samples classes when no main class is declared")
    void samplesWhenMainIsUnknown() throws IOException {

        Path jar = TestJars.builder(directory, "sampled.jar")
                .classFile("com/example/A.class", 52)
                .classFile("com/example/B.class", 61)
                .build();

        JarInfo info = JarInspector.inspect(jar);

        assertEquals(17, info.javaVersion(), "the highest class version is the one that must load");
    }

}
