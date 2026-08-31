package top.vulpine.catalog.modrinth.model;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * One result of a project search.
 *
 * <p>Shaped differently from {@link ModrinthProject}: the id lives in {@code project_id} rather
 * than {@code id}, and the license is a plain identifier rather than an object.</p>
 */
@Getter
@Accessors(fluent = true)
public final class SearchHit {

    private String projectId;
    private String slug;
    private String title;
    private String description;
    private String author;
    private String projectType;
    private int downloads;
    private int follows;
    private String iconUrl;
    private String license;
    private String latestVersion;
    private List<String> categories;
    private List<String> versions;

}
