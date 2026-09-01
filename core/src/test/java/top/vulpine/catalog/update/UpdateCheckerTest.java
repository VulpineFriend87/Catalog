package top.vulpine.catalog.update;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.vulpine.catalog.json.Json;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.update.model.ServerPlatform;
import top.vulpine.catalog.update.model.ServerTarget;
import top.vulpine.catalog.update.model.UpdateCandidate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UpdateChecker")
class UpdateCheckerTest {

    @TempDir
    Path data;

    private TrackingStore store;
    private final Map<String, ModrinthVersion> offered = new HashMap<>();
    private final List<List<String>> asked = new ArrayList<>();
    private final List<List<ReleaseChannel>> askedChannels = new ArrayList<>();

    private static final ServerTarget PAPER = ServerTarget.builder()
            .platform(ServerPlatform.PAPER)
            .gameVersion("1.21.4")
            .javaVersion(21)
            .build();

    @BeforeEach
    void setUp() {
        store = new TrackingStore(data.resolve("tracked.json"));
    }

    private UpdateChecker checker() {
        return new UpdateChecker((hashes, loaders, gameVersions, channels) -> {
            asked.add(hashes);
            askedChannels.add(channels);
            Map<String, ModrinthVersion> answer = new HashMap<>();
            hashes.forEach(hash -> {
                if (offered.containsKey(hash)) {
                    answer.put(hash, offered.get(hash));
                }
            });
            return answer;
        }, store);
    }

    private static ModrinthVersion version(String id, String number, String published, String... loaders) {

        StringBuilder tags = new StringBuilder();

        for (String loader : loaders.length == 0 ? new String[]{"paper"} : loaders) {
            tags.append(tags.length() == 0 ? "" : ",").append('"').append(loader).append('"');
        }

        return Json.gson().fromJson("""
                {
                  "id": "%s",
                  "project_id": "PROJ",
                  "version_number": "%s",
                  "version_type": "release",
                  "date_published": "%s",
                  "loaders": [%s],
                  "game_versions": ["1.21.4"],
                  "files": [],
                  "dependencies": []
                }
                """.formatted(id, number, published, tags), ModrinthVersion.class);
    }

    /** Tracks a plugin at the given version, and says what Modrinth would offer for it. */
    private TrackedPlugin tracked(String projectId, String hash, ModrinthVersion installed,
                                  ModrinthVersion available) {

        TrackedPlugin plugin = TrackedPlugin.of(installed, projectId + ".jar", hash,
                ReleaseChannel.RELEASE, "test");
        plugin.projectId(projectId);
        plugin.name(projectId);

        store.put(plugin);

        if (available != null) {
            offered.put(hash, available);
        }

        return plugin;
    }

    @Test
    @DisplayName("a newer version is reported as an update")
    void findsAnUpdate() {

        tracked("A", "hash-a",
                version("v1", "1.0", "2026-01-01T00:00:00Z"),
                version("v2", "2.0", "2026-06-01T00:00:00Z"));

        List<UpdateCandidate> candidates = checker().check(PAPER);

        assertEquals(1, candidates.size());
        assertEquals("1.0", candidates.get(0).from());
        assertEquals("2.0", candidates.get(0).to());
    }

    @Test
    @DisplayName("the version already installed is not an update")
    void ignoresTheSameVersion() {

        ModrinthVersion current = version("v1", "1.0", "2026-01-01T00:00:00Z");
        tracked("A", "hash-a", current, current);

        assertTrue(checker().check(PAPER).isEmpty());
    }

    @Test
    @DisplayName("an older build is never offered, even when Modrinth returns one")
    void refusesDowngrades() {

        // Narrowing to one game version can make the newest compatible build older than what is
        // installed, which is how an operator on an older Minecraft version gets offered a rollback.
        tracked("A", "hash-a",
                version("v2", "2.0", "2026-06-01T00:00:00Z"),
                version("v1", "1.0", "2026-01-01T00:00:00Z"));

        assertTrue(checker().check(PAPER).isEmpty(), "a different id alone is not enough");
    }

    @Test
    @DisplayName("a pinned plugin is not even asked about")
    void skipsPinnedPlugins() {

        TrackedPlugin plugin = tracked("A", "hash-a",
                version("v1", "1.0", "2026-01-01T00:00:00Z"),
                version("v2", "2.0", "2026-06-01T00:00:00Z"));

        plugin.pinToCurrent();

        assertTrue(checker().check(PAPER).isEmpty());
        assertTrue(asked.isEmpty(), "asking would only waste a slot in the request");
    }

    @Test
    @DisplayName("plugins are grouped so each channel costs one request")
    void groupsByChannel() {

        tracked("A", "hash-a", version("v1", "1.0", "2026-01-01T00:00:00Z"), null);
        tracked("B", "hash-b", version("v1", "1.0", "2026-01-01T00:00:00Z"), null);

        store.byProjectId("B").channel(ReleaseChannel.BETA);

        checker().check(PAPER);

        assertEquals(2, asked.size(), "one request per channel, not one per plugin");
        assertTrue(askedChannels.contains(List.of(ReleaseChannel.RELEASE)));
        assertTrue(askedChannels.contains(List.of(ReleaseChannel.RELEASE, ReleaseChannel.BETA)),
                "a server on beta also accepts releases");
    }

    @Test
    @DisplayName("everything on one channel costs a single request")
    void oneRequestForOneChannel() {

        tracked("A", "hash-a", version("v1", "1.0", "2026-01-01T00:00:00Z"), null);
        tracked("B", "hash-b", version("v1", "1.0", "2026-01-01T00:00:00Z"), null);
        tracked("C", "hash-c", version("v1", "1.0", "2026-01-01T00:00:00Z"), null);

        checker().check(PAPER);

        assertEquals(1, asked.size());
        assertEquals(3, asked.get(0).size(), "all three hashes in the one request");
    }

    @Test
    @DisplayName("notes whether a candidate names this exact platform")
    void labelsThePlatform() {

        tracked("A", "hash-a",
                version("v1", "1.0", "2026-01-01T00:00:00Z"),
                version("v2", "2.0", "2026-06-01T00:00:00Z", "spigot"));

        assertFalse(checker().check(PAPER).get(0).declaresPlatform(),
                "a spigot build runs on Paper, but does not name it");
    }

    @Test
    @DisplayName("an empty store asks nothing at all")
    void asksNothingWhenEmpty() {

        assertTrue(checker().check(PAPER).isEmpty());
        assertTrue(asked.isEmpty());
    }

}
