package top.vulpine.catalog.update.model;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.tracking.model.TrackedPlugin;

/**
 * A newer version of a tracked plugin that this server can actually run.
 *
 * <p>Everything here has already passed the checks Modrinth can answer: the game version, the
 * loader and the release channel were all filtered server-side. What remains unverified is what
 * only the jar itself can say, and that is checked after downloading, before anything is staged.</p>
 */
@Getter
@Builder
@Accessors(fluent = true)
public final class UpdateCandidate {

    private final TrackedPlugin plugin;

    private final ModrinthVersion version;

    /**
     * Whether the candidate names this exact platform, rather than one it happens to be compatible
     * with.
     *
     * <p>A label, not a verdict. It matters on Folia, where a build that does not declare support
     * will be refused by the server, and is merely informational everywhere else.</p>
     */
    private final boolean declaresPlatform;

    /**
     * Whether the candidate names this server's exact Minecraft version.
     *
     * <p>False means it was accepted as a build for an earlier patch of the same line, which is
     * usually an author who did not tick the newest box and occasionally a build that genuinely
     * predates a breaking change. Worth saying out loud either way.</p>
     */
    private final boolean declaresGameVersion;

    /**
     * @return the version currently installed
     */
    public String from() {
        return plugin.versionNumber();
    }

    /**
     * @return the version on offer
     */
    public String to() {
        return version.versionNumber();
    }

    @Override
    public String toString() {
        return plugin.displayName() + " " + from() + " -> " + to();
    }

}
