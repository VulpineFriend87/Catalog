package top.vulpine.catalog.modrinth;

import top.vulpine.catalog.Errors;
import top.vulpine.catalog.install.DependencyResolver;
import top.vulpine.catalog.install.InstallException;
import top.vulpine.catalog.modrinth.model.ModrinthProject;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.modrinth.model.SearchResults;
import top.vulpine.catalog.platform.Platform;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.update.model.ServerTarget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Asking Modrinth about projects, narrowed to what this server can run.
 *
 * <p>Every method blocks, so none may be called on the server main thread.</p>
 */
public final class Projects {

    private final Platform platform;
    private final ModrinthClient modrinth;
    private final TrackingStore tracking;

    public Projects(Platform platform, ModrinthClient modrinth, TrackingStore tracking) {
        this.platform = platform;
        this.modrinth = modrinth;
        this.tracking = tracking;
    }

    /**
     * Looks a project up by its id or slug.
     *
     * @param idOrSlug what to look for
     * @return the project, or null if there is no such thing
     */
    public ModrinthProject project(String idOrSlug) {

        try {
            return modrinth.project(idOrSlug).join();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Searches Modrinth, narrowed to what this server could actually run.
     *
     * @param query  the search text
     * @param limit  how many results to return
     * @param offset where to start
     * @return one page of results
     */
    public SearchResults search(String query, int limit, int offset) {

        ServerTarget target = platform.target();
        List<List<String>> facets = new ArrayList<>();

        facets.add(List.of("project_type:plugin"));

        List<String> loaders = new ArrayList<>();

        for (String loader : target.loaders()) {
            loaders.add("categories:" + loader);
        }

        facets.add(loaders);
        facets.add(List.of("versions:" + target.gameVersion()));

        return modrinth.search(query, facets, limit, offset).join();
    }

    /**
     * Every build of a project this server could run, newest first, on any channel.
     *
     * @param idOrSlug the project to list
     * @return the compatible versions, newest published first
     */
    public List<ModrinthVersion> compatibleVersions(String idOrSlug) {

        ServerTarget target = platform.target();

        for (List<String> tier : target.platform().loaderTiers()) {

            List<ModrinthVersion> versions;

            try {
                versions = modrinth.versions(idOrSlug, tier, target.gameVersions()).join();
            } catch (Exception e) {
                throw new InstallException("Could not reach Modrinth: " + Errors.rootMessage(e), e);
            }

            if (!versions.isEmpty()) {
                List<ModrinthVersion> sorted = new ArrayList<>(versions);
                sorted.sort(Comparator.comparing(ModrinthVersion::datePublished).reversed());
                return sorted;
            }
        }

        return List.of();
    }

    /**
     * Every build a project has ever published, newest first, not filtered.
     *
     * @param idOrSlug the project to list
     * @return every version, newest published first
     */
    public List<ModrinthVersion> allVersions(String idOrSlug) {

        List<ModrinthVersion> versions;

        try {
            versions = new ArrayList<>(modrinth.versions(idOrSlug, null, null).join());
        } catch (Exception e) {
            throw new InstallException("Could not reach Modrinth: " + Errors.rootMessage(e), e);
        }

        versions.sort(Comparator.comparing(ModrinthVersion::datePublished).reversed());
        return versions;
    }

    /**
     * The build an install would fetch for a project.
     *
     * @param idOrSlug the project to look at
     * @return the build to offer, or null when nothing published runs here
     */
    public ModrinthVersion installTarget(String idOrSlug) {
        return installTarget(compatibleVersions(idOrSlug));
    }

    /**
     * The build an install would fetch: the newest stable one, or the newest of anything when the
     * project has never published a stable build for this server.
     *
     * @param compatible what this server can run, newest first
     * @return the build to offer, or null when the list is empty
     */
    public static ModrinthVersion installTarget(List<ModrinthVersion> compatible) {

        for (ModrinthVersion version : compatible) {
            if (version.versionType() == ReleaseChannel.RELEASE) {
                return version;
            }
        }

        return compatible.isEmpty() ? null : compatible.get(0);
    }

    /**
     * What a build depends on.
     *
     * @param version the build being considered
     * @return the resolution, with required dependencies followed through the whole graph
     */
    public DependencyResolver.Resolution dependenciesOf(ModrinthVersion version) {

        DependencyResolver resolver = new DependencyResolver(
                this::installTarget,
                projectId -> tracking.byProjectId(projectId) != null);

        return resolver.resolve(version);
    }

}
