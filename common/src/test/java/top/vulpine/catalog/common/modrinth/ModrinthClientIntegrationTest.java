package top.vulpine.catalog.common.modrinth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import top.vulpine.catalog.common.modrinth.model.ModrinthProject;
import top.vulpine.catalog.common.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.common.modrinth.model.SearchResults;
import top.vulpine.catalog.common.modrinth.model.VersionFile;
import top.vulpine.catalog.common.modrinth.model.VersionType;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ModrinthClient} against the live Modrinth API.
 *
 * <p>Excluded from the normal build because it needs network access and depends on a third party
 * staying up. Run it with {@code gradlew :common:integrationTest} whenever the client or the models
 * change.</p>
 *
 * <p>Field binding is the thing most worth checking here. A misspelled field silently deserialises
 * to null rather than failing, so the assertions below deliberately reach into every field Catalog
 * actually depends on.</p>
 */
@Tag("integration")
@DisplayName("ModrinthClient against the live API")
class ModrinthClientIntegrationTest {

    /** A small, stable plugin that publishes releases only — used as a known-good fixture. */
    private static final String FIXTURE_SLUG = "simplelobby";

    private static ModrinthClient client;

    @BeforeAll
    static void setUp() {
        client = ModrinthClient.builder("VulpineFriend87/Catalog/test (integration test)").build();
    }

    @AfterAll
    static void tearDown() {
        client.close();
    }

    @Test
    @DisplayName("version listings bind every field Catalog relies on")
    void versionsBindCompletely() {

        List<ModrinthVersion> versions = client.versions(FIXTURE_SLUG, null, null).join();

        assertFalse(versions.isEmpty(), "the fixture project should have published versions");

        ModrinthVersion newest = versions.get(0);
        VersionFile primary = newest.primaryFile();

        assertAll(
                () -> assertNotNull(newest.id(), "id"),
                () -> assertNotNull(newest.projectId(), "projectId (snake_case binding)"),
                () -> assertNotNull(newest.versionNumber(), "versionNumber (snake_case binding)"),
                () -> assertNotNull(newest.versionType(), "versionType (enum binding)"),
                () -> assertNotNull(newest.datePublished(), "datePublished (Instant adapter)"),
                () -> assertFalse(newest.loaders().isEmpty(), "loaders"),
                () -> assertFalse(newest.gameVersions().isEmpty(), "gameVersions (snake_case binding)"),
                () -> assertNotNull(primary, "a primary file"),
                () -> assertNotNull(primary.sha512(), "sha512 (nested hashes object)"),
                () -> assertNotNull(primary.url(), "download url"),
                () -> assertNotNull(primary.filename(), "filename"),
                () -> assertTrue(primary.size() > 0, "file size")
        );
    }

    @Test
    @DisplayName("a file is identified by its hash, and identification round-trips")
    void identifyRoundTrips() {

        List<ModrinthVersion> versions = client.versions(FIXTURE_SLUG, null, null).join();
        ModrinthVersion expected = versions.get(versions.size() - 1);
        String hash = expected.primaryFile().sha512();

        Map<String, ModrinthVersion> identified = client.identify(List.of(hash)).join();

        assertEquals(1, identified.size(), "one hash in, one version out");
        assertEquals(expected.id(), identified.get(hash).id(), "the same version we started from");
    }

    @Test
    @DisplayName("an unknown hash is absent rather than an error")
    void unknownHashIsAbsent() {

        String nonsense = "0".repeat(128);

        Map<String, ModrinthVersion> identified = client.identify(List.of(nonsense)).join();

        assertTrue(identified.isEmpty(), "an unrecognised jar must not fail the whole lookup");
    }

    @Test
    @DisplayName("the oldest version reports the newest one as an update")
    void latestFindsAnUpdate() {

        List<ModrinthVersion> versions = client.versions(FIXTURE_SLUG, null, null).join();
        ModrinthVersion oldest = versions.get(versions.size() - 1);
        ModrinthVersion newest = versions.get(0);
        String hash = oldest.primaryFile().sha512();

        Map<String, ModrinthVersion> latest = client.latest(
                List.of(hash),
                List.of("paper"),
                oldest.gameVersions(),
                EnumSet.of(VersionType.RELEASE)
        ).join();

        assertEquals(newest.id(), latest.get(hash).id(), "the newest compatible release");
        assertTrue(latest.get(hash).datePublished().isAfter(oldest.datePublished()), "and it is newer");
    }

    @Test
    @DisplayName("version_types is honoured server-side")
    void channelFilterIsHonoured() {

        List<ModrinthVersion> versions = client.versions(FIXTURE_SLUG, null, null).join();
        ModrinthVersion oldest = versions.get(versions.size() - 1);
        String hash = oldest.primaryFile().sha512();

        // The fixture publishes releases only, so asking for alphas alone must find nothing.
        // This is what lets a whole-server check cost one request regardless of channel.
        Map<String, ModrinthVersion> alphasOnly = client.latest(
                List.of(hash),
                List.of("paper"),
                oldest.gameVersions(),
                EnumSet.of(VersionType.ALPHA)
        ).join();

        assertTrue(alphasOnly.isEmpty(), "no alpha exists, so nothing should come back");
    }

    @Test
    @DisplayName("an incompatible game version yields no update")
    void gameVersionFilterIsHonoured() {

        List<ModrinthVersion> versions = client.versions(FIXTURE_SLUG, null, null).join();
        ModrinthVersion oldest = versions.get(versions.size() - 1);
        String hash = oldest.primaryFile().sha512();

        Map<String, ModrinthVersion> onAncientServer = client.latest(
                List.of(hash),
                List.of("paper"),
                List.of("1.8.9"),
                EnumSet.allOf(VersionType.class)
        ).join();

        assertTrue(onAncientServer.isEmpty(), "nothing supports 1.8.9, so nothing should be offered");
    }

    @Test
    @DisplayName("projects bind, including the nested license")
    void projectBinds() {

        ModrinthProject project = client.project(FIXTURE_SLUG).join();

        assertAll(
                () -> assertEquals(FIXTURE_SLUG, project.slug()),
                () -> assertNotNull(project.id()),
                () -> assertNotNull(project.title()),
                () -> assertNotNull(project.projectType(), "projectType (snake_case binding)"),
                () -> assertNotNull(project.license(), "license (nested object)"),
                () -> assertNotNull(project.license().id(), "license id"),
                () -> assertFalse(project.loaders().isEmpty(), "loaders"),
                () -> assertFalse(project.gameVersions().isEmpty(), "gameVersions")
        );
    }

    @Test
    @DisplayName("search returns bound hits")
    void searchBinds() {

        SearchResults results = client.search(
                "essentials",
                List.of(List.of("project_type:plugin")),
                5,
                0
        ).join();

        assertFalse(results.hits().isEmpty(), "a common query should match something");
        assertTrue(results.totalHits() > 0, "totalHits (snake_case binding)");
        assertNotNull(results.hits().get(0).projectId(), "projectId (snake_case binding)");
        assertNotNull(results.hits().get(0).title(), "title");
    }

}
