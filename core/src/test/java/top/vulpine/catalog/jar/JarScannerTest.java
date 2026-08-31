package top.vulpine.catalog.jar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JarScanner")
class JarScannerTest {

    @TempDir
    Path plugins;

    @Test
    @DisplayName("hashes and inspects every jar in the folder")
    void scansTheFolder() throws IOException {

        TestJars.builder(plugins, "one.jar").descriptor("plugin.yml", "name: One\nversion: 1.0\n").build();
        TestJars.builder(plugins, "two.jar").descriptor("plugin.yml", "name: Two\nversion: 2.0\n").build();

        ScanResult result = new JarScanner(plugins).scan();

        assertEquals(2, result.jars().size());
        assertNotNull(result.byFileName("one.jar").sha512());
        assertEquals(128, result.byFileName("one.jar").sha512().length(), "SHA-512 is 64 bytes of hex");
        assertEquals("Two", result.byFileName("two.jar").info().pluginName());
    }

    @Test
    @DisplayName("ignores non-jars and does not descend into subdirectories")
    void ignoresEverythingElse() throws IOException {

        TestJars.builder(plugins, "real.jar").descriptor("plugin.yml", "name: Real\n").build();
        Files.writeString(plugins.resolve("config.yml"), "not a plugin");

        // Both of these are Catalog's own territory and must never be hashed as plugins.
        Files.createDirectories(plugins.resolve("Catalog"));
        TestJars.builder(plugins.resolve("update"), "queued.jar").descriptor("plugin.yml", "name: Real\n").build();

        ScanResult result = new JarScanner(plugins).scan();

        assertEquals(1, result.jars().size());
        assertEquals("real.jar", result.jars().get(0).fileName());
    }

    @Test
    @DisplayName("reuses hashes for files that have not changed")
    void reusesUnchangedEntries() throws IOException {

        TestJars.builder(plugins, "stable.jar").descriptor("plugin.yml", "name: Stable\n").build();

        JarScanner scanner = new JarScanner(plugins);

        ScanResult first = scanner.scan();
        ScanResult second = scanner.scan(first.jars());

        assertSame(first.jars().get(0), second.jars().get(0), "an untouched jar is not re-hashed");
    }

    @Test
    @DisplayName("re-hashes a file that was replaced by hand")
    void detectsManualReplacement() throws IOException {

        Path jar = TestJars.builder(plugins, "swapped.jar")
                .descriptor("plugin.yml", "name: Swapped\nversion: 1.0\n")
                .build();

        ScanResult first = new JarScanner(plugins).scan();
        String originalHash = first.jars().get(0).sha512();

        Files.delete(jar);
        TestJars.builder(plugins, "swapped.jar")
                .descriptor("plugin.yml", "name: Swapped\nversion: 2.0\n")
                .file("extra.txt", "changed on disk")
                .build();

        ScanResult second = new JarScanner(plugins).scan(first.jars());

        assertEquals("2.0", second.jars().get(0).info().pluginVersion());
        assertTrue(!originalHash.equals(second.jars().get(0).sha512()), "the new content hashes differently");
    }

    @Test
    @DisplayName("reports two jars declaring the same plugin")
    void reportsDuplicates() throws IOException {

        TestJars.builder(plugins, "LuckPerms-5.4.jar")
                .descriptor("plugin.yml", "name: LuckPerms\nversion: 5.4\n")
                .build();

        TestJars.builder(plugins, "LuckPerms-5.6.jar")
                .descriptor("plugin.yml", "name: LuckPerms\nversion: 5.6\n")
                .build();

        TestJars.builder(plugins, "Vault.jar")
                .descriptor("plugin.yml", "name: Vault\nversion: 1.7\n")
                .build();

        ScanResult result = new JarScanner(plugins).scan();

        assertEquals(1, result.duplicates().size());
        assertEquals("LuckPerms", result.duplicates().get(0).pluginName());
        assertEquals(2, result.duplicates().get(0).jars().size());
    }

    @Test
    @DisplayName("jars without a descriptor never count as duplicates of each other")
    void librariesAreNotDuplicates() throws IOException {

        TestJars.builder(plugins, "lib-a.jar").classFile("a/A.class", 61).build();
        TestJars.builder(plugins, "lib-b.jar").classFile("b/B.class", 61).build();

        ScanResult result = new JarScanner(plugins).scan();

        assertEquals(2, result.jars().size());
        assertTrue(result.duplicates().isEmpty());
    }

    @Test
    @DisplayName("a missing folder scans to nothing rather than failing")
    void toleratesMissingFolder() {

        ScanResult result = new JarScanner(plugins.resolve("does-not-exist")).scan();

        assertTrue(result.jars().isEmpty());
        assertNull(result.byHash("whatever"));
    }

    @Test
    @DisplayName("finds a jar by hash")
    void findsByHash() throws IOException {

        TestJars.builder(plugins, "findme.jar").descriptor("plugin.yml", "name: FindMe\n").build();

        ScanResult result = new JarScanner(plugins).scan();
        List<InstalledJar> jars = result.jars();

        assertSame(jars.get(0), result.byHash(jars.get(0).sha512()));
    }

}
