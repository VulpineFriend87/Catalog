package top.vulpine.catalog.trash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.vulpine.catalog.install.InstallException;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.trash.model.TrashEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TrashBin")
class TrashBinTest {

    @TempDir
    Path root;

    private TrashBin bin() {
        return new TrashBin(root.resolve("trash"));
    }

    private Path plugins() throws IOException {
        return Files.createDirectories(root.resolve("plugins"));
    }

    private Path jar(String name, String contents) throws IOException {
        return Files.writeString(plugins().resolve(name), contents);
    }

    private static TrackedPlugin tracked(String projectId, String name) {

        TrackedPlugin plugin = new TrackedPlugin();

        plugin.projectId(projectId);
        plugin.slug(name.toLowerCase());
        plugin.name(name);
        plugin.versionId("ver1");
        plugin.versionNumber("5.4");
        plugin.sha512("abc");
        plugin.channel(ReleaseChannel.BETA);

        return plugin;
    }

    @Test
    @DisplayName("keeps what it was told about the plugin, so a restore does not need Modrinth")
    void records() throws IOException {

        TrashBin bin = bin();
        TrashEntry entry = bin.bin(jar("LuckPerms.jar", "x"), tracked("abc123", "LuckPerms"), "vulpine")
                .entry();

        assertAll(
                () -> assertEquals("LuckPerms.jar", entry.fileName()),
                () -> assertEquals("abc123", entry.projectId()),
                () -> assertEquals("5.4", entry.versionNumber()),
                () -> assertEquals(ReleaseChannel.BETA, entry.channel()),
                () -> assertEquals("vulpine", entry.removedBy()),
                () -> assertEquals("LuckPerms", entry.displayName())
        );
    }

    @Test
    @DisplayName("removing the same plugin twice keeps both copies, each restorable on its own")
    void separateRemovals() throws IOException {

        TrashBin bin = bin();

        String first = bin.bin(jar("Vault.jar", "one"), tracked("v", "Vault"), "a").entry().storedAs();
        String second = bin.bin(jar("Vault.jar", "two"), tracked("v", "Vault"), "a").entry().storedAs();

        assertAll(
                () -> assertNotEqualsIds(first, second),
                () -> assertEquals(2, bin.list().size()),
                () -> assertNotNull(bin.find(first)),
                () -> assertNotNull(bin.find(second))
        );
    }

    private static void assertNotEqualsIds(String first, String second) {
        assertFalse(first.equals(second), "two removals must not share a stored name");
    }

    @Test
    @DisplayName("restores the exact removal asked for, not the newest one")
    void restoresTheOneAskedFor() throws IOException {

        TrashBin bin = bin();

        TrashEntry older = bin.bin(jar("Vault.jar", "old"), tracked("v", "Vault"), "a").entry();
        bin.bin(jar("Vault.jar", "new"), tracked("v", "Vault"), "a");

        bin.restore(older, plugins().resolve("Vault.jar"));

        assertAll(
                () -> assertEquals("old", Files.readString(plugins().resolve("Vault.jar"))),
                () -> assertNull(bin.find(older.storedAs()), "the restored copy leaves the bin"),
                () -> assertEquals(1, bin.list().size(), "the other removal is untouched")
        );
    }

    @Test
    @DisplayName("refuses to restore over a file that is already there")
    void refusesToOverwrite() throws IOException {

        TrashBin bin = bin();
        TrashEntry entry = bin.bin(jar("Vault.jar", "one"), tracked("v", "Vault"), "a").entry();

        jar("Vault.jar", "something else");

        assertThrows(InstallException.class, () -> bin.restore(entry, plugins().resolve("Vault.jar")));
        assertNotNull(bin.find(entry.storedAs()), "a refused restore leaves the copy in the bin");
    }

    @Test
    @DisplayName("lists newest first, because the wanted one is almost always the last removed")
    void newestFirst() throws IOException {

        TrashBin bin = bin();

        bin.bin(jar("A.jar", "a"), tracked("a", "A"), "x");
        bin.bin(jar("B.jar", "b"), tracked("b", "B"), "x");

        List<TrashEntry> listed = bin.list();

        assertEquals("B", listed.get(0).displayName());
    }

    /**
     * The sidecar is written on a best-effort basis and its absence must not strand a jar: the
     * stored name alone still says what the file was called and when it went.
     */
    @Test
    @DisplayName("still lists and restores a removal whose metadata never got written")
    void survivesMissingMetadata() throws IOException {

        TrashBin bin = bin();
        TrashEntry entry = bin.bin(jar("Orphan.jar", "o"), null, "x").entry();

        Files.delete(root.resolve("trash").resolve(entry.storedAs() + ".json"));

        List<TrashEntry> listed = bin.list();

        assertAll(
                () -> assertEquals(1, listed.size()),
                () -> assertEquals("Orphan.jar", listed.get(0).fileName()),
                () -> assertNotNull(listed.get(0).removedAt(), "the time is in the stored name"),
                () -> assertNull(listed.get(0).projectId())
        );

        bin.restore(listed.get(0), plugins().resolve("Orphan.jar"));
        assertTrue(Files.exists(plugins().resolve("Orphan.jar")));
    }

    @Test
    @DisplayName("prunes what is older than the window and keeps the rest")
    void prunes() throws IOException {

        TrashBin bin = bin();

        TrashEntry kept = bin.bin(jar("New.jar", "n"), tracked("n", "New"), "x").entry();

        assertEquals(1, bin.prune(Duration.ofDays(30), Instant.now().plus(Duration.ofDays(31))));
        assertNull(bin.find(kept.storedAs()));
    }

    @Test
    @DisplayName("a retention of zero keeps everything forever")
    void keepsForever() throws IOException {

        TrashBin bin = bin();
        bin.bin(jar("New.jar", "n"), tracked("n", "New"), "x");

        assertEquals(0, bin.prune(Duration.ZERO, Instant.now().plus(Duration.ofDays(3650))));
        assertEquals(1, bin.list().size());
    }

    @Test
    @DisplayName("emptying deletes every removal and its metadata")
    void empties() throws IOException {

        TrashBin bin = bin();

        bin.bin(jar("A.jar", "a"), tracked("a", "A"), "x");
        bin.bin(jar("B.jar", "b"), tracked("b", "B"), "x");

        assertEquals(2, bin.empty());

        assertEquals(List.of(), bin.list());

        try (var left = Files.list(root.resolve("trash"))) {
            assertEquals(0, left.count(), "the sidecars go with the jars");
        }
    }

    @Test
    @DisplayName("an empty bin is an empty list, not a failure")
    void emptyBin() {
        assertEquals(List.of(), bin().list());
    }

    /**
     * The stored name reaches the bin from a click payload, so it has to be treated as input rather
     * than as something Catalog wrote.
     */
    @Test
    @DisplayName("refuses a stored name that tries to walk out of the trash directory")
    void refusesTraversal() throws IOException {

        TrashBin bin = bin();
        bin.bin(jar("Vault.jar", "v"), tracked("v", "Vault"), "x");

        // Put a jar back where the traversal would land, so the guard is what stops it rather
        // than the file happening not to be there.
        jar("Vault.jar", "still here");

        assertAll(
                () -> assertNull(bin.find("../plugins/Vault.jar")),
                () -> assertNull(bin.find("..\\\\plugins\\\\Vault.jar")),
                () -> assertNull(bin.find(null))
        );
    }

}
