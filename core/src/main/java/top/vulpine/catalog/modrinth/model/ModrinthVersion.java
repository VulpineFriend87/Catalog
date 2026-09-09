package top.vulpine.catalog.modrinth.model;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One published version of a Modrinth project.
 */
@Getter
@Accessors(fluent = true)
public final class ModrinthVersion {

    private String id;
    private String projectId;
    private String name;
    private String versionNumber;
    private String changelog;
    private ReleaseChannel versionType;
    private Instant datePublished;
    private int downloads;
    private List<String> loaders;
    private List<String> gameVersions;
    private List<VersionFile> files;
    private List<Dependency> dependencies;

    /**
     * The plugin jar itself.
     *
     * @return the primary file, or null if the version carries no files at all
     */
    public VersionFile primaryFile() {

        if (files == null || files.isEmpty()) {
            return null;
        }

        return files.stream()
                .filter(VersionFile::primary)
                .findFirst()
                .orElse(files.get(0));
    }

    /**
     * Every file other than the primary one.
     *
     * @return the non-primary files, never null
     */
    public List<VersionFile> extraFiles() {

        if (files == null) {
            return Collections.emptyList();
        }

        VersionFile primary = primaryFile();

        return files.stream()
                .filter(file -> file != primary)
                .collect(Collectors.toList());
    }

    /**
     * Dependencies of the given kind.
     *
     * @param type the relationship to filter by
     * @return the matching dependencies, never null
     */
    public List<Dependency> dependenciesOf(DependencyType type) {

        if (dependencies == null) {
            return Collections.emptyList();
        }

        return dependencies.stream()
                .filter(dependency -> dependency.dependencyType() == type)
                .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return "ModrinthVersion(" + versionNumber + ", id=" + id + ", type=" + versionType + ")";
    }

}
