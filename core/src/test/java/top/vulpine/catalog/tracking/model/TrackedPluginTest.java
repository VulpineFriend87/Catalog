package top.vulpine.catalog.tracking.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.vulpine.catalog.json.Json;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TrackedPlugin")
class TrackedPluginTest {

    @Test
    @DisplayName("an update can be rolled back")
    void rollsBackAnUpdate() {

        TrackedPlugin plugin = at(version("v1", "2026-01-01T00:00:00Z"));
        plugin.moveTo(version("v2", "2026-06-01T00:00:00Z"), "A.jar", "hash-2");

        assertTrue(plugin.canRollBack());
    }

    @Test
    @DisplayName("a rollback does not offer the newer build as a rollback")
    void doesNotRollForward() {

        TrackedPlugin plugin = at(version("v2", "2026-06-01T00:00:00Z"));
        plugin.moveTo(version("v1", "2026-01-01T00:00:00Z"), "A.jar", "hash-1");

        assertFalse(plugin.canRollBack());
    }

    @Test
    @DisplayName("a previous build recorded without a date can still be rolled back to")
    void rollsBackWithoutADate() {

        TrackedPlugin plugin = at(version("v2", "2026-06-01T00:00:00Z"));
        plugin.previousVersionId("v1");

        assertTrue(plugin.canRollBack());
    }

    @Test
    @DisplayName("nothing to roll back to on a first install")
    void nothingBeforeTheFirstBuild() {
        assertFalse(at(version("v1", "2026-01-01T00:00:00Z")).canRollBack());
    }

    private static TrackedPlugin at(ModrinthVersion version) {
        return TrackedPlugin.of(version, "A.jar", "hash", ReleaseChannel.RELEASE, "test");
    }

    private static ModrinthVersion version(String id, String published) {

        return Json.gson().fromJson("""
                {
                  "id": "%s",
                  "project_id": "PROJ",
                  "version_number": "%s",
                  "version_type": "release",
                  "date_published": "%s",
                  "loaders": ["paper"],
                  "game_versions": ["1.21.4"],
                  "files": [],
                  "dependencies": []
                }
                """.formatted(id, id, published), ModrinthVersion.class);
    }

}
