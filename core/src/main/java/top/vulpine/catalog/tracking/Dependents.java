package top.vulpine.catalog.tracking;

import top.vulpine.catalog.CatalogAction;
import top.vulpine.catalog.Errors;
import top.vulpine.catalog.modrinth.model.Dependency;
import top.vulpine.catalog.modrinth.model.DependencyType;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.commons.log.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Which installed plugins require another one.
 *
 * <p>Blocks, so it must be called off the server main thread.</p>
 */
public final class Dependents {

    private final TrackingStore tracking;
    private final Function<Collection<String>, Map<String, ModrinthVersion>> identify;

    /**
     * @param identify hashes to the build each one is, which is what carries the dependency list
     */
    public Dependents(TrackingStore tracking,
                      Function<Collection<String>, Map<String, ModrinthVersion>> identify) {
        this.tracking = tracking;
        this.identify = identify;
    }

    /**
     * The installed plugins that name this one as a required dependency.
     *
     * @param plugin the plugin about to be removed
     * @return what would be left without a dependency, empty when nothing needs it
     */
    public List<TrackedPlugin> of(TrackedPlugin plugin) {

        if (plugin.projectId() == null) {
            return List.of();
        }

        List<String> hashes = new ArrayList<>();

        for (TrackedPlugin other : tracking.all()) {

            if (!other.projectId().equals(plugin.projectId()) && other.sha512() != null) {
                hashes.add(other.sha512());
            }
        }

        if (hashes.isEmpty()) {
            return List.of();
        }

        Map<String, ModrinthVersion> installed;

        try {
            installed = identify.apply(hashes);
        } catch (Exception e) {
            // Saying nothing depends on it would be worse than saying nothing at all, so the
            // caller is told to carry on without a claim either way.
            Logger.debug(CatalogAction.TRACK, "Could not check what depends on "
                    + plugin.displayName() + ": " + Errors.rootMessage(e));
            return List.of();
        }

        List<TrackedPlugin> dependents = new ArrayList<>();

        for (TrackedPlugin other : tracking.all()) {

            ModrinthVersion version = other.sha512() == null ? null : installed.get(other.sha512());

            if (version == null || other.projectId().equals(plugin.projectId())) {
                continue;
            }

            for (Dependency dependency : version.dependenciesOf(DependencyType.REQUIRED)) {

                if (plugin.projectId().equals(dependency.projectId())) {
                    dependents.add(other);
                    break;
                }
            }
        }

        return dependents;
    }

}
