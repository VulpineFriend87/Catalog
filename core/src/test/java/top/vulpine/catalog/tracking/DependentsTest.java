package top.vulpine.catalog.tracking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.vulpine.catalog.json.Json;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.tracking.model.TrackedPlugin;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding what an installed plugin would leave broken.
 */
class DependentsTest {

    @TempDir
    Path directory;

    private TrackingStore store;
    private final Map<String, ModrinthVersion> catalogue = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        store = new TrackingStore(directory.resolve("tracked.json"));
        store.load();
        catalogue.clear();
    }

    private Dependents dependents() {

        return new Dependents(store, hashes -> {

            Map<String, ModrinthVersion> answer = new HashMap<>();

            for (String hash : hashes) {
                if (catalogue.containsKey(hash)) {
                    answer.put(hash, catalogue.get(hash));
                }
            }

            return answer;
        });
    }

    /**
     * Versions are bound from JSON rather than constructed, since they exist to be read from the
     * API and have no public constructor.
     */
    private TrackedPlugin install(String projectId, String name, String... dependencies) {

        StringBuilder json = new StringBuilder("{\"id\":\"v-").append(projectId)
                .append("\",\"project_id\":\"").append(projectId)
                .append("\",\"version_number\":\"1.0\",\"dependencies\":[");

        for (int i = 0; i < dependencies.length; i += 2) {
            json.append(i == 0 ? "" : ",")
                    .append("{\"project_id\":\"").append(dependencies[i])
                    .append("\",\"dependency_type\":\"").append(dependencies[i + 1]).append("\"}");
        }

        catalogue.put("hash-" + projectId,
                Json.gson().fromJson(json.append("]}").toString(), ModrinthVersion.class));

        TrackedPlugin tracked = new TrackedPlugin();
        tracked.projectId(projectId);
        tracked.name(name);
        tracked.sha512("hash-" + projectId);
        store.put(tracked);

        return tracked;
    }

    @Test
    @DisplayName("a plugin nothing needs can be removed without a word")
    void nothingDependsOnIt() {
        TrackedPlugin luckperms = install("luckperms", "LuckPerms");
        install("worldedit", "WorldEdit");
        assertTrue(dependents().of(luckperms).isEmpty());
    }

    @Test
    @DisplayName("a plugin another one requires is reported")
    void requiredByOne() {

        TrackedPlugin vault = install("vault", "Vault");
        install("essentials", "Essentials", "vault", "required");

        List<TrackedPlugin> found = dependents().of(vault);

        assertEquals(1, found.size());
        assertEquals("Essentials", found.get(0).displayName());
    }

    @Test
    @DisplayName("every dependent is listed, not just the first")
    void requiredByMany() {

        TrackedPlugin vault = install("vault", "Vault");
        install("essentials", "Essentials", "vault", "required");
        install("shop", "Shop", "vault", "required");

        assertEquals(2, dependents().of(vault).size());
    }

    @Test
    @DisplayName("an optional dependency never counts")
    void optionalDoesNotCount() {

        TrackedPlugin papi = install("papi", "PlaceholderAPI");
        install("essentials", "Essentials", "papi", "optional");

        assertTrue(dependents().of(papi).isEmpty());
    }

    @Test
    @DisplayName("a plugin does not depend on itself")
    void neverItself() {
        TrackedPlugin vault = install("vault", "Vault", "vault", "required");
        assertTrue(dependents().of(vault).isEmpty());
    }

    @Test
    @DisplayName("a dependency on something not installed is ignored")
    void untrackedDependencyIsIgnored() {

        TrackedPlugin vault = install("vault", "Vault");
        install("essentials", "Essentials", "something-else", "required");

        assertTrue(dependents().of(vault).isEmpty());
    }

    @Test
    @DisplayName("Modrinth being unreachable claims nothing either way")
    void lookupFailureClaimsNothing() {

        TrackedPlugin vault = install("vault", "Vault");
        install("essentials", "Essentials", "vault", "required");

        Dependents failing = new Dependents(store, hashes -> {
            throw new IllegalStateException("no network");
        });

        assertTrue(failing.of(vault).isEmpty());
    }

    @Test
    @DisplayName("a plugin with no project id is never checked")
    void untrackedPluginIsSkipped() {

        install("essentials", "Essentials", "vault", "required");

        TrackedPlugin unknown = new TrackedPlugin();
        unknown.name("Something");

        assertTrue(dependents().of(unknown).isEmpty());
    }

}
