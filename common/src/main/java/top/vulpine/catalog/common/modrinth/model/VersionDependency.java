package top.vulpine.catalog.common.modrinth.model;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * A dependency declared by a Modrinth version.
 *
 * <p>Either {@link #projectId()} or {@link #versionId()} may be null: a dependency can point at a
 * whole project, meaning "any compatible version", or at one exact version.</p>
 */
@Getter
@Accessors(fluent = true)
public final class VersionDependency {

    private String versionId;
    private String projectId;
    private String fileName;
    private DependencyType dependencyType;

}
