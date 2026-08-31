package top.vulpine.catalog.modrinth.model;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * The license of a Modrinth project.
 */
@Getter
@Accessors(fluent = true)
public final class License {

    private String id;
    private String name;
    private String url;

}
