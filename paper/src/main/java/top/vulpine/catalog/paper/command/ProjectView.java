package top.vulpine.catalog.paper.command;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.Accessors;
import top.vulpine.catalog.modrinth.model.ModrinthProject;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.tracking.model.TrackedPlugin;

import java.util.List;

/**
 * Everything the info page shows about one project, gathered before a single line is drawn.
 *
 * <p>It exists so that {@link Messages} never has to reach for the network or the tracking store
 * halfway through building a message: the page is rendered from a value that is already complete,
 * or it is not rendered at all.</p>
 */
@Getter
@Builder
@Accessors(fluent = true)
public final class ProjectView {

    private final ModrinthProject project;

    /** Who to credit, or null if the team could not be fetched. */
    private final String author;

    /** The newest build on the channel being followed, or null if that channel has none. */
    private final ModrinthVersion latest;

    /**
     * What the install button would fetch: the newest stable build, or the newest of anything when
     * the project has never published a stable one. Null only when nothing here runs at all.
     */
    private final ModrinthVersion installTarget;

    /** Null when the project is not installed here. */
    private final TrackedPlugin installed;

    private final boolean updateAvailable;

    /** The loaders this server can use, so the project's list can show which ones apply. */
    @Singular
    private final List<String> platformLoaders;

    @Singular
    private final List<Requirement> requirements;

    /**
     * A project the latest build declares it needs.
     *
     * @param name      the project's title, or its id when the title could not be fetched
     * @param installed whether this server already has it
     */
    public record Requirement(String name, boolean installed) {
    }

}
