package top.vulpine.catalog.paper.command;

import top.vulpine.catalog.modrinth.model.DependencyType;

/**
 * One project a build declares.
 */
public record DependencyView(String name, String slug, String version, boolean installed,
                             DependencyType type, boolean available) {

    /** Whether it has to be here before the plugin that named it will run. */
    public boolean blocking() {
        return type == DependencyType.REQUIRED && !installed;
    }

    /** Whether it is here and the author said it must not be. */
    public boolean conflicting() {
        return type == DependencyType.INCOMPATIBLE && installed;
    }

    /** Whether something could be installed for it right now. */
    public boolean offerable() {
        return !installed && available;
    }

}
