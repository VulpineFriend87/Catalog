package top.vulpine.catalog.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.vulpine.catalog.json.Json;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.update.model.UpdateCandidate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AutoUpdatePolicy")
class AutoUpdatePolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    private static final AutoUpdatePolicy POLICY = new AutoUpdatePolicy(120);

    @Test
    @DisplayName("a plugin that did not ask for it is never updated on its own")
    void skipsPluginsWithAutoUpdateOff() {

        UpdateCandidate candidate = candidate(plugin(false, TrackedPlugin.INHERIT_SOAK),
                "2026-01-01T00:00:00Z");

        assertTrue(POLICY.readyToApply(List.of(candidate), NOW).isEmpty());
    }

    @Test
    @DisplayName("a build old enough to have soaked is applied")
    void appliesSoakedBuilds() {

        UpdateCandidate candidate = candidate(plugin(true, TrackedPlugin.INHERIT_SOAK),
                "2026-09-03T09:00:00Z");

        assertEquals(1, POLICY.readyToApply(List.of(candidate), NOW).size());
    }

    @Test
    @DisplayName("a build published minutes ago is left alone")
    void waitsOutTheSoakWindow() {

        // The whole point: an author who hotfixes an hour later should never have caught anyone.
        UpdateCandidate candidate = candidate(plugin(true, TrackedPlugin.INHERIT_SOAK),
                "2026-09-03T11:40:00Z");

        assertTrue(POLICY.soaking(candidate, NOW));
        assertTrue(POLICY.readyToApply(List.of(candidate), NOW).isEmpty());
    }

    @Test
    @DisplayName("a plugin's own soak window overrides the default")
    void honoursPerPluginSoak() {

        UpdateCandidate candidate = candidate(plugin(true, 10), "2026-09-03T11:40:00Z");

        assertEquals(10, POLICY.soakMinutes(candidate.plugin()));
        assertFalse(POLICY.soaking(candidate, NOW), "twenty minutes old, and it only waits ten");
    }

    @Test
    @DisplayName("a soak of zero installs immediately")
    void allowsNoSoakAtAll() {

        UpdateCandidate candidate = candidate(plugin(true, 0), "2026-09-03T11:59:59Z");

        assertEquals(1, POLICY.readyToApply(List.of(candidate), NOW).size());
    }

    @Test
    @DisplayName("a build already staged is not downloaded again")
    void skipsWhatIsAlreadyWaiting() {

        TrackedPlugin plugin = plugin(true, 0);
        plugin.pendingRestart(true);

        assertTrue(POLICY.readyToApply(List.of(candidate(plugin, "2026-01-01T00:00:00Z")), NOW).isEmpty());
    }

    @Test
    @DisplayName("a held plugin is never updated on its own")
    void skipsHeldPlugins() {

        TrackedPlugin plugin = plugin(true, 0);
        plugin.pinToCurrent();

        assertTrue(POLICY.readyToApply(List.of(candidate(plugin, "2026-01-01T00:00:00Z")), NOW).isEmpty());
    }

    private static TrackedPlugin plugin(boolean autoUpdate, int soakMinutes) {

        TrackedPlugin plugin = new TrackedPlugin();
        plugin.projectId("A");
        plugin.versionId("old");
        plugin.name("Example");
        plugin.autoUpdate(autoUpdate);
        plugin.soakMinutes(soakMinutes);

        return plugin;
    }

    private static UpdateCandidate candidate(TrackedPlugin plugin, String published) {

        ModrinthVersion version = Json.gson().fromJson("""
                {
                  "id": "new",
                  "project_id": "A",
                  "version_number": "2.0",
                  "version_type": "release",
                  "date_published": "%s",
                  "loaders": ["paper"],
                  "game_versions": ["1.21.4"],
                  "files": [],
                  "dependencies": []
                }
                """.formatted(published), ModrinthVersion.class);

        return UpdateCandidate.builder()
                .plugin(plugin)
                .version(version)
                .declaresPlatform(true)
                .build();
    }

}
