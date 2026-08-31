package top.vulpine.catalog.common.modrinth.model;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * A Modrinth project, as returned by the project endpoints.
 *
 * <p>Search results come back as {@link SearchHit} instead, which carries a smaller and slightly
 * differently shaped set of fields.</p>
 */
@Getter
@Accessors(fluent = true)
public final class ModrinthProject {

    private String id;
    private String slug;
    private String title;
    private String description;
    private String body;
    private String projectType;
    private int downloads;
    private int followers;
    private String iconUrl;
    private License license;
    private List<String> categories;
    private List<String> gameVersions;
    private List<String> loaders;
    private List<String> versions;

    @Override
    public String toString() {
        return "ModrinthProject(" + slug + ", id=" + id + ")";
    }

}
