package top.vulpine.catalog.install;

import top.vulpine.catalog.modrinth.model.Dependency;
import top.vulpine.catalog.modrinth.model.DependencyType;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Works out what else has to be installed for a build to run.
 */
public final class DependencyResolver {

    /**
     * How deep the graph is allowed to go before the answer is refused.
     */
    private static final int MAX_DEPTH = 10;

    /**
     * This is an interface so every rule below can be tested without a network.
     */
    @FunctionalInterface
    public interface Versions {

        /**
         * @param projectId the project to look at
         * @return the build this server would install for it, or null if there is none
         */
        ModrinthVersion newestFor(String projectId);
    }

    private final Versions versions;
    private final Predicate<String> installed;

    /**
     * @param versions  where to find the build a project would install as
     * @param installed whether a project id is already tracked on this server
     */
    public DependencyResolver(Versions versions, Predicate<String> installed) {
        this.versions = versions;
        this.installed = installed;
    }

    /**
     * Everything a build declares, resolved against what is already here.
     *
     * <p>Blocks on the lookups, so it must be called off the main thread.</p>
     *
     * @param root the build about to be installed
     * @return what it requires, what it can use, and what it must not sit alongside
     */
    public Resolution resolve(ModrinthVersion root) {

        List<Requirement> required = new ArrayList<>();

        // The build being installed counts as already visited. Without that, a project reachable
        // from its own dependencies comes back as a requirement of itself.
        Set<String> walked = new LinkedHashSet<>();

        if (root.projectId() != null) {
            walked.add(root.projectId());
        }

        follow(root, 0, walked, required);

        List<Requirement> optional = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Dependency dependency : root.dependenciesOf(DependencyType.OPTIONAL)) {

            String projectId = dependency.projectId();

            if (projectId == null || !seen.add(projectId)) {
                continue;
            }

            boolean have = installed.test(projectId);
            optional.add(new Requirement(projectId, have ? null : versions.newestFor(projectId),
                    have, DependencyType.OPTIONAL));
        }

        List<String> conflicts = new ArrayList<>();

        for (Dependency dependency : root.dependenciesOf(DependencyType.INCOMPATIBLE)) {

            String projectId = dependency.projectId();

            if (projectId != null && installed.test(projectId) && !conflicts.contains(projectId)) {
                conflicts.add(projectId);
            }
        }

        return new Resolution(required, optional, conflicts);
    }

    /**
     * Walks the required dependencies of one build, adding each project once.
     *
     * <p>A dependency already on the server is recorded and not followed. Whatever it requires is
     * either satisfied or already broken, and either way it is not something this install caused or
     * can be blamed for.</p>
     */
    private void follow(ModrinthVersion version, int depth, Set<String> visited,
                        List<Requirement> out) {

        if (depth >= MAX_DEPTH) {
            return;
        }

        for (Dependency dependency : version.dependenciesOf(DependencyType.REQUIRED)) {

            String projectId = dependency.projectId();

            // The visited set is what makes a cycle stop. Nothing prevents an author declaring one,
            // and two plugins that require each other would otherwise be followed forever.
            if (projectId == null || !visited.add(projectId)) {
                continue;
            }

            if (installed.test(projectId)) {
                out.add(new Requirement(projectId, null, true, DependencyType.REQUIRED));
                continue;
            }

            ModrinthVersion build = versions.newestFor(projectId);
            out.add(new Requirement(projectId, build, false, DependencyType.REQUIRED));

            if (build != null) {
                follow(build, depth + 1, visited, out);
            }
        }
    }

    /**
     * One project a build named, and what this server has to say about it.
     *
     * @param projectId the project Modrinth was pointed at
     * @param available the build that would be installed, null when it is already here or when
     *                  nothing published runs on this server
     * @param installed whether it is already tracked
     * @param type      what the author called it
     */
    public record Requirement(String projectId, ModrinthVersion available, boolean installed,
                              DependencyType type) {

        /** Whether this has to be installed before the plugin that named it will run. */
        public boolean blocking() {
            return type == DependencyType.REQUIRED && !installed;
        }

        /** Whether it is wanted but nothing published for this server can satisfy it. */
        public boolean unavailable() {
            return !installed && available == null;
        }

    }

    /**
     * What a build needs, in the three shapes that lead to different decisions.
     *
     * @param required  every required project, flattened through the whole graph
     * @param optional  what the root alone names as optional
     * @param conflicts projects declared incompatible that are installed anyway
     */
    public record Resolution(List<Requirement> required, List<Requirement> optional,
                             List<String> conflicts) {

        /** The required projects that are not here yet, in the order they were found. */
        public List<Requirement> missing() {

            List<Requirement> missing = new ArrayList<>();

            for (Requirement requirement : required) {
                if (requirement.blocking()) {
                    missing.add(requirement);
                }
            }

            return missing;
        }

        /** Whether everything required is either installed or installable. */
        public boolean satisfiable() {

            for (Requirement requirement : required) {
                if (requirement.unavailable()) {
                    return false;
                }
            }

            return true;
        }

    }

}
