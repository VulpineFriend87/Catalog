package top.vulpine.catalog.paper.command;

import top.vulpine.catalog.modrinth.model.DependencyType;

/**
 * One project a build declares, gathered before a line is drawn.
 *
 * <p>Names and slugs come from Modrinth in one bulk request, so a screen listing five dependencies
 * costs one lookup rather than five. Anything that could not be fetched keeps its project id as its
 * name, which is still enough to act on.</p>
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
