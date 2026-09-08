package top.vulpine.catalog.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.vulpine.catalog.json.Json;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DependencyResolver")
class DependencyResolverTest {

    /** What Modrinth would hand back for each project. */
    private final Map<String, ModrinthVersion> catalogue = new HashMap<>();

    /** What is already on the server. */
    private final Set<String> installed = new HashSet<>();

    /** Which projects were actually looked up, to prove the walk stops where it should. */
    private final List<String> asked = new ArrayList<>();

    private DependencyResolver resolver() {
        return new DependencyResolver(projectId -> {
            asked.add(projectId);
            return catalogue.get(projectId);
        }, installed::contains);
    }

    /**
     * Versions are bound from JSON rather than constructed, since they exist to be read from the
     * API and have no public constructor.
     */
    private ModrinthVersion version(String projectId, String... dependencies) {

        StringBuilder json = new StringBuilder("{\"id\":\"v-").append(projectId)
                .append("\",\"project_id\":\"").append(projectId)
                .append("\",\"version_number\":\"1.0\",\"dependencies\":[");

        for (int i = 0; i < dependencies.length; i += 2) {
            json.append(i == 0 ? "" : ",")
                    .append("{\"project_id\":\"").append(dependencies[i])
                    .append("\",\"dependency_type\":\"").append(dependencies[i + 1]).append("\"}");
        }

        ModrinthVersion parsed = Json.gson().fromJson(json.append("]}").toString(),
                ModrinthVersion.class);

        catalogue.put(projectId, parsed);
        return parsed;
    }

    @Test
    @DisplayName("names the required project a build declares")
    void required() {

        version("viaversion");
        ModrinthVersion root = version("viabackwards", "viaversion", "required");

        DependencyResolver.Resolution resolution = resolver().resolve(root);

        assertAll(
                () -> assertEquals(1, resolution.required().size()),
                () -> assertEquals("viaversion", resolution.required().get(0).projectId()),
                () -> assertTrue(resolution.required().get(0).blocking()),
                () -> assertEquals(1, resolution.missing().size()),
                () -> assertTrue(resolution.satisfiable())
        );
    }

    @Test
    @DisplayName("an installed requirement is recorded but not counted as missing")
    void alreadyInstalled() {

        version("viaversion");
        installed.add("viaversion");

        DependencyResolver.Resolution resolution =
                resolver().resolve(version("viabackwards", "viaversion", "required"));

        assertAll(
                () -> assertEquals(1, resolution.required().size()),
                () -> assertTrue(resolution.required().get(0).installed()),
                () -> assertFalse(resolution.required().get(0).blocking()),
                () -> assertEquals(List.of(), resolution.missing())
        );
    }

    /**
     * The list the install screen shows is what is about to be written to disk. Which dependency
     * pulled in which is not a fact anyone acts on, so the graph arrives flat.
     */
    @Test
    @DisplayName("follows requirements through the graph and returns them flat")
    void transitive() {

        version("c");
        version("b", "c", "required");
        ModrinthVersion root = version("a", "b", "required");

        DependencyResolver.Resolution resolution = resolver().resolve(root);

        assertEquals(List.of("b", "c"),
                resolution.missing().stream().map(DependencyResolver.Requirement::projectId).toList());
    }

    @Test
    @DisplayName("does not follow a requirement that is already installed")
    void stopsAtWhatIsThere() {

        version("c");
        version("b", "c", "required");
        installed.add("b");

        DependencyResolver.Resolution resolution =
                resolver().resolve(version("a", "b", "required"));

        assertAll(
                () -> assertEquals(1, resolution.required().size()),
                () -> assertFalse(asked.contains("b"), "an installed project is not looked up"),
                () -> assertFalse(asked.contains("c"), "nor is anything behind it")
        );
    }

    /**
     * Nothing stops an author declaring that two plugins require each other. Without the visited
     * set that would be followed until the stack ran out.
     */
    @Test
    @DisplayName("a cycle terminates instead of being followed forever")
    void cycle() {

        ModrinthVersion a = version("a", "b", "required");
        version("b", "a", "required");

        DependencyResolver.Resolution resolution = resolver().resolve(a);

        assertAll(
                () -> assertEquals(List.of("b"), resolution.missing().stream()
                        .map(DependencyResolver.Requirement::projectId).toList()),
                () -> assertEquals(1, asked.size(), "each project is looked up once")
        );
    }

    @Test
    @DisplayName("stops walking a graph deeper than anything real")
    void depthCap() {

        for (int i = 0; i < 40; i++) {
            version("p" + i, "p" + (i + 1), "required");
        }

        DependencyResolver.Resolution resolution = resolver().resolve(catalogue.get("p0"));

        assertTrue(resolution.missing().size() <= 10,
                "the walk is capped, got " + resolution.missing().size());
    }

    @Test
    @DisplayName("says so when nothing published for this server can satisfy a requirement")
    void unavailable() {

        ModrinthVersion root = version("a", "ghost", "required");

        DependencyResolver.Resolution resolution = resolver().resolve(root);

        assertAll(
                () -> assertNull(resolution.missing().get(0).available()),
                () -> assertTrue(resolution.missing().get(0).unavailable()),
                () -> assertFalse(resolution.satisfiable())
        );
    }

    /**
     * Optional dependencies are never installed on their own, so what they in turn require is not
     * this server's problem and is not fetched.
     */
    @Test
    @DisplayName("reads optional dependencies from the root only")
    void optionalIsNotFollowed() {

        version("deep");
        version("papi", "deep", "required");
        ModrinthVersion root = version("a", "papi", "optional");

        DependencyResolver.Resolution resolution = resolver().resolve(root);

        assertAll(
                () -> assertEquals(1, resolution.optional().size()),
                () -> assertEquals("papi", resolution.optional().get(0).projectId()),
                () -> assertEquals(List.of(), resolution.missing(), "optional never blocks"),
                () -> assertFalse(asked.contains("deep"))
        );
    }

    @Test
    @DisplayName("reports an incompatible project only when it is actually installed")
    void conflicts() {

        ModrinthVersion root = version("a", "enemy", "incompatible");

        assertEquals(List.of(), resolver().resolve(root).conflicts());

        installed.add("enemy");
        assertEquals(List.of("enemy"), resolver().resolve(root).conflicts());
    }

    @Test
    @DisplayName("an embedded dependency is already in the jar and asks for nothing")
    void embedded() {

        ModrinthVersion root = version("a", "shaded", "embedded");
        DependencyResolver.Resolution resolution = resolver().resolve(root);

        assertAll(
                () -> assertEquals(List.of(), resolution.missing()),
                () -> assertEquals(List.of(), resolution.optional()),
                () -> assertEquals(List.of(), resolution.conflicts())
        );
    }

}
