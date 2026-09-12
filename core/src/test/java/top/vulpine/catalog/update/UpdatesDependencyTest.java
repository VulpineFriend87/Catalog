package top.vulpine.catalog.update;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.vulpine.catalog.history.History;
import top.vulpine.catalog.install.DependencyResolver;
import top.vulpine.catalog.json.Json;
import top.vulpine.catalog.modrinth.model.DependencyType;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.platform.Platform;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.update.model.ServerTarget;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dependency gate on automatic updates.
 */
class UpdatesDependencyTest {

    @TempDir
    Path directory;

    private TrackingStore store;

    /**
     * A server that records rather than writes, so nothing here touches a plugins folder.
     */
    private final class Recording implements Platform {

        @Override
        public Path pluginsFolder() {
            return directory;
        }

        @Override
        public ServerTarget target() {
            return ServerTarget.builder().gameVersion("1.21.4").javaVersion(21).build();
        }

        @Override
        public String ownFileName() {
            return "Catalog.jar";
        }

        @Override
        public String stagingName() {
            return "update";
        }

        @Override
        public void applyAtRestart(Path from, String fileName) {
        }

        @Override
        public boolean isStaged(String fileName) {
            return false;
        }

        @Override
        public boolean cancelStaged(String fileName) {
            return true;
        }

    }

    @BeforeEach
    void setUp() throws Exception {

        store = new TrackingStore(directory.resolve("tracked.json"));
        store.load();

        TrackedPlugin plugin = new TrackedPlugin();
        plugin.projectId("P7dR8mSH");
        plugin.name("LuckPerms");
        plugin.versionId("old");
        plugin.autoUpdate(true);
        plugin.datePublished(Instant.now().minusSeconds(86_400));

        store.put(plugin);
    }

    private History history() {
        return new History(directory.resolve("history.json"));
    }

    private Updates updates(DependencyResolver.Resolution resolution) {
        return new Updates(new Recording(), null, store, null, () -> 0, version -> resolution,
                history());
    }

    private static DependencyResolver.Resolution needing(String projectId, boolean installed) {

        DependencyResolver.Requirement requirement = new DependencyResolver.Requirement(
                projectId, null, installed, DependencyType.REQUIRED);

        return new DependencyResolver.Resolution(List.of(requirement), List.of(), List.of());
    }

    /**
     * Versions are bound from JSON rather than constructed, since they exist to be read from the
     * API and have no public constructor.
     */
    private static ModrinthVersion version() {
        return Json.gson().fromJson(
                "{\"id\":\"new\",\"project_id\":\"P7dR8mSH\",\"version_number\":\"5.5.0\"}",
                ModrinthVersion.class);
    }

    @Test
    @DisplayName("a build whose required dependencies are installed has nothing missing")
    void satisfiedBuildIsNotBlocked() {
        assertTrue(updates(needing("Vault", true)).missingFor(version()).isEmpty());
    }

    @Test
    @DisplayName("a build that needs something not installed reports it")
    void missingDependencyIsReported() {

        List<DependencyResolver.Requirement> missing = updates(needing("Vault", false))
                .missingFor(version());

        assertEquals(1, missing.size());
        assertEquals("Vault", missing.get(0).projectId());
    }

    @Test
    @DisplayName("an optional dependency never blocks an update")
    void optionalDependencyDoesNotBlock() {

        DependencyResolver.Requirement optional = new DependencyResolver.Requirement(
                "PlaceholderAPI", null, false, DependencyType.OPTIONAL);

        DependencyResolver.Resolution resolution = new DependencyResolver.Resolution(
                List.of(), List.of(optional), List.of());

        assertTrue(updates(resolution).missingFor(version()).isEmpty());
    }

    @Test
    @DisplayName("a resolver that cannot reach Modrinth is not treated as a missing dependency")
    void resolverFailureIsNotAMissingDependency() {

        Updates failing = new Updates(new Recording(), null, store, null, () -> 0,
                version -> {
                    throw new IllegalStateException("no network");
                }, history());

        assertTrue(failing.missingFor(version()).isEmpty());
    }

    @Test
    @DisplayName("nothing is held back before a check has run")
    void nothingBlockedInitially() {
        assertTrue(updates(needing("Vault", false)).blocked().isEmpty());
    }

}
