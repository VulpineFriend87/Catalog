package top.vulpine.catalog.tracking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.vulpine.catalog.json.Json;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TrackingStore")
class TrackingStoreTest {

    @TempDir
    Path data;

    private Path file() {
        return data.resolve("tracked.json");
    }

    /**
     * Modrinth versions are bound from JSON rather than constructed, since they exist to be read
     * from the API and have no public constructor.
     */
    private static ModrinthVersion version(String id, String number) {
        return Json.gson().fromJson("""
                {
                  "id": "%s",
                  "project_id": "AABBCCDD",
                  "version_number": "%s",
                  "version_type": "release",
                  "date_published": "2026-08-08T10:00:00Z",
                  "loaders": ["paper"],
                  "game_versions": ["1.21.4"],
                  "files": [],
                  "dependencies": []
                }
                """.formatted(id, number), ModrinthVersion.class);
    }

    @Test
    @DisplayName("a missing file is a first run, not an error")
    void missingFileIsEmpty() {

        TrackingStore store = new TrackingStore(file());
        store.load();

        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("saves and loads back every field")
    void roundTrips() {

        TrackingStore store = new TrackingStore(file());

        TrackedPlugin tracked = TrackedPlugin.of(version("EWUUCch9", "2.0"), "(catalog) SimpleLobby.jar",
                "abc123", ReleaseChannel.BETA, "VulpineFriend87");
        tracked.name("SimpleLobby");
        tracked.slug("simplelobby");
        tracked.autoUpdate(true);
        tracked.soakMinutes(240);
        tracked.extraFiles().put("extension.jar", "plugins/Typewriter/extensions");

        store.put(tracked);
        store.save();

        TrackingStore reloaded = new TrackingStore(file());
        reloaded.load();

        TrackedPlugin back = reloaded.byProjectId("AABBCCDD");

        assertAll(
                () -> assertNotNull(back),
                () -> assertEquals("EWUUCch9", back.versionId()),
                () -> assertEquals("2.0", back.versionNumber()),
                () -> assertEquals("(catalog) SimpleLobby.jar", back.fileName()),
                () -> assertEquals("abc123", back.sha512()),
                () -> assertEquals(ReleaseChannel.BETA, back.channel(), "enum survives"),
                () -> assertTrue(back.autoUpdate()),
                () -> assertEquals(240, back.soakMinutes()),
                () -> assertEquals(Instant.parse("2026-08-08T10:00:00Z"), back.datePublished(), "Instant survives"),
                () -> assertNotNull(back.installedAt()),
                () -> assertEquals("VulpineFriend87", back.installedBy()),
                () -> assertEquals("plugins/Typewriter/extensions", back.extraFiles().get("extension.jar"))
        );
    }

    @Test
    @DisplayName("a record written without settings loads with the intended defaults")
    void appliesDefaultsToPartialRecords() throws IOException {

        Files.writeString(file(), "[{\"project_id\":\"AABBCCDD\",\"version_id\":\"x\"}]");

        TrackingStore store = new TrackingStore(file());
        store.load();

        TrackedPlugin tracked = store.byProjectId("AABBCCDD");

        assertAll(
                () -> assertFalse(tracked.autoUpdate(), "auto-update is off unless asked for"),
                () -> assertEquals(ReleaseChannel.RELEASE, tracked.channel()),
                () -> assertEquals(TrackedPlugin.INHERIT_SOAK, tracked.soakMinutes()),
                () -> assertTrue(tracked.explicit()),
                () -> assertFalse(tracked.isPinned()),
                () -> assertNotNull(tracked.extraFiles(), "never null, so callers can just use it")
        );
    }

    @Test
    @DisplayName("a corrupt file is quarantined and reported, never silently discarded")
    void quarantinesCorruptState() throws IOException {

        Files.writeString(file(), "{ this is not json at all");

        TrackingStore store = new TrackingStore(file());

        TrackingException thrown = assertThrows(TrackingException.class, store::load);

        assertTrue(thrown.getMessage().contains("corrupt"), "the message points at the moved file");
        assertFalse(Files.exists(file()), "the bad file is moved out of the way");

        try (var entries = Files.list(data)) {
            assertTrue(entries.anyMatch(path -> path.getFileName().toString().contains(".corrupt-")),
                    "and kept, because it holds settings the operator will want back");
        }
    }

    @Test
    @DisplayName("an empty file is empty, not corrupt")
    void toleratesBlankFile() throws IOException {

        Files.writeString(file(), "   \n");

        TrackingStore store = new TrackingStore(file());
        store.load();

        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("saving leaves no temporary file behind")
    void savesAtomically() throws IOException {

        TrackingStore store = new TrackingStore(file());
        store.put(TrackedPlugin.of(version("v1", "1.0"), "a.jar", "hash", ReleaseChannel.RELEASE, "test"));
        store.save();

        try (var entries = Files.list(data)) {
            assertEquals(1, entries.count(), "only tracked.json, no leftover .tmp");
        }
    }

    @Test
    @DisplayName("records with no project id are dropped, since nothing could address them")
    void dropsUnusableRecords() throws IOException {

        Files.writeString(file(), "[{\"version_id\":\"orphan\"},{\"project_id\":\"AABBCCDD\"}]");

        TrackingStore store = new TrackingStore(file());
        store.load();

        assertEquals(1, store.size());
        assertNotNull(store.byProjectId("AABBCCDD"));
    }

    @Test
    @DisplayName("finds records by file name and by hash")
    void findsByFileNameAndHash() {

        TrackingStore store = new TrackingStore(file());
        store.put(TrackedPlugin.of(version("v1", "1.0"), "(catalog) LuckPerms.jar", "deadbeef",
                ReleaseChannel.RELEASE, "test"));

        assertAll(
                () -> assertNotNull(store.byFileName("(catalog) LuckPerms.jar")),
                () -> assertNotNull(store.byHash("deadbeef")),
                () -> assertNull(store.byFileName("something-else.jar")),
                () -> assertNull(store.byHash("cafebabe"))
        );
    }

    @Test
    @DisplayName("pinning to current resolves to a concrete version instead of a sentinel")
    void pinResolvesImmediately() {

        TrackedPlugin tracked = TrackedPlugin.of(version("EWUUCch9", "2.0"), "a.jar", "hash",
                ReleaseChannel.RELEASE, "test");

        tracked.pinToCurrent();

        assertTrue(tracked.isPinned());
        assertEquals("EWUUCch9", tracked.pinnedVersionId(),
                "so the pin cannot drift if the file is swapped by hand");
    }

    @Test
    @DisplayName("lists what is waiting for a restart")
    void listsPendingRestart() {

        TrackingStore store = new TrackingStore(file());

        TrackedPlugin queued = TrackedPlugin.of(version("v1", "1.0"), "a.jar", "h1", ReleaseChannel.RELEASE, "test");
        queued.projectId("AAA");
        queued.pendingRestart(true);

        TrackedPlugin settled = TrackedPlugin.of(version("v2", "2.0"), "b.jar", "h2", ReleaseChannel.RELEASE, "test");
        settled.projectId("BBB");

        store.put(queued);
        store.put(settled);

        assertEquals(1, store.pendingRestart().size());
    }

}
