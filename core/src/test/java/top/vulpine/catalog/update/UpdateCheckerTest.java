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
import java.util.Comparator;
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

    /** What Modrinth holds, per installed hash. */
    private final Map<String, List<ModrinthVersion>> catalogue = new HashMap<>();

    private final List<Ask> asked = new ArrayList<>();

    private record Ask(List<String> hashes, List<String> loaders, List<ReleaseChannel> channels) {
    }

    private static final ServerTarget PAPER = ServerTarget.builder()
            .platform(ServerPlatform.PAPER)
            .gameVersion("1.21.4")
            .javaVersion(21)
            .build();

    @BeforeEach
    void setUp() {
        store = new TrackingStore(data.resolve("tracked.json"));
    }

    /**
     * Behaves like the update endpoint: filters by loader, then returns the newest match.
     */
    private UpdateChecker checker() {

        return new UpdateChecker((hashes, loaders, gameVersions, channels) -> {

            asked.add(new Ask(List.copyOf(hashes), List.copyOf(loaders), List.copyOf(channels)));

            Map<String, ModrinthVersion> answer = new HashMap<>();

            for (String hash : hashes) {
                catalogue.getOrDefault(hash, List.of()).stream()
                        .filter(version -> version.loaders().stream().anyMatch(loaders::contains))
                        .max(Comparator.comparing(ModrinthVersion::datePublished))
                        .ifPresent(version -> answer.put(hash, version));
            }

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

    /** Tracks a plugin at the given version, and says what Modrinth holds for that project. */
    private TrackedPlugin tracked(String projectId, String hash, ModrinthVersion installed,
                                  ModrinthVersion... available) {

        TrackedPlugin plugin = TrackedPlugin.of(installed, projectId + ".jar", hash,
                ReleaseChannel.RELEASE, "test");
        plugin.projectId(projectId);
        plugin.name(projectId);

        store.put(plugin);
        catalogue.put(hash, List.of(available));

        return plugin;
    }

    @Test
    @DisplayName("a newer version is reported as an update")
    void findsAnUpdate() {

        ModrinthVersion newer = version("v2", "2.0", "2026-06-01T00:00:00Z");
        tracked("A", "hash-a", version("v1", "1.0", "2026-01-01T00:00:00Z"), newer);

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
    @DisplayName("the build for this platform wins over a newer one for a lesser platform")
    void prefersTheBuildForThisPlatform() {

        // FastAsyncWorldEdit publishes -Paper and -Bukkit as two versions seconds apart. Asking for
        // every loader at once and taking the newest would swap the Paper build for the Bukkit one
        // and call it an update.
        ModrinthVersion paperBuild = version("paper-2154", "2.15.4", "2026-08-16T16:32:23Z", "paper");
        ModrinthVersion bukkitBuild = version("bukkit-2154", "2.15.4", "2026-08-16T16:32:26Z", "spigot");

        tracked("FAWE", "hash-fawe", paperBuild, paperBuild, bukkitBuild);

        assertTrue(checker().check(PAPER).isEmpty(), "the Paper build is already installed");
        assertEquals(1, asked.size(), "and the wider group is never asked");
        assertEquals(List.of("paper"), asked.get(0).loaders());
    }

    @Test
    @DisplayName("a plugin published only for an older platform is still found")
    void widensForPluginsNotAnswered() {

        ModrinthVersion newer = version("v2", "2.0", "2026-06-01T00:00:00Z", "spigot");
        tracked("A", "hash-a", version("v1", "1.0", "2026-01-01T00:00:00Z", "spigot"), newer);

        List<UpdateCandidate> candidates = checker().check(PAPER);

        assertEquals(1, candidates.size(), "the closure exists exactly for this case");
        assertEquals(2, asked.size());
        assertEquals(List.of("spigot", "bukkit"), asked.get(1).loaders());
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
    @DisplayName("each channel is asked for separately, cumulatively")
    void groupsByChannel() {

        tracked("A", "hash-a", version("v1", "1.0", "2026-01-01T00:00:00Z"));
        tracked("B", "hash-b", version("v1", "1.0", "2026-01-01T00:00:00Z"));

        store.byProjectId("B").channel(ReleaseChannel.BETA);

        checker().check(PAPER);

        List<List<ReleaseChannel>> channels = asked.stream().map(Ask::channels).distinct().toList();

        assertTrue(channels.contains(List.of(ReleaseChannel.RELEASE)));
        assertTrue(channels.contains(List.of(ReleaseChannel.RELEASE, ReleaseChannel.BETA)),
                "a server on beta also accepts releases");
    }

    @Test
    @DisplayName("every plugin on a channel goes in one request")
    void batchesEachChannel() {

        tracked("A", "hash-a", version("v1", "1.0", "2026-01-01T00:00:00Z"));
        tracked("B", "hash-b", version("v1", "1.0", "2026-01-01T00:00:00Z"));
        tracked("C", "hash-c", version("v1", "1.0", "2026-01-01T00:00:00Z"));

        checker().check(PAPER);

        assertEquals(3, asked.get(0).hashes().size(), "all three hashes in the one request");
    }

    @Test
    @DisplayName("notes whether a candidate names this exact platform")
    void labelsThePlatform() {

        tracked("A", "hash-a",
                version("v1", "1.0", "2026-01-01T00:00:00Z", "spigot"),
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
