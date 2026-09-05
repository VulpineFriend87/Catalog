package top.vulpine.catalog.trash.model;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;

import java.time.Instant;

/**
 * What was removed, written next to the jar in the trash so it can be put back.
 *
 * <p>Everything needed to restore is recorded here rather than looked up later, because by the
 * time someone wants a plugin back the version they had may no longer be the newest, and on
 * Modrinth it may not be listed at all.</p>
 */
@Getter
@Builder
@Accessors(fluent = true)
public final class TrashEntry {

    /** The name the jar had in the plugins folder, which is where a restore puts it back. */
    private String fileName;

    /** The name of the jar inside the trash directory, unique per removal. */
    private String storedAs;

    private String projectId;
    private String slug;
    private String name;

    private String versionId;
    private String versionNumber;
    private String sha512;

    /** The channel the plugin was following, so restoring it does not silently reset the setting. */
    private ReleaseChannel channel;

    private String removedBy;
    private Instant removedAt;

    /**
     * What to call this on screen: the Modrinth title when it is known, and the file name when the
     * jar was never tracked or its metadata was lost.
     *
     * @return a name that is never null
     */
    public String displayName() {
        return name != null ? name : fileName;
    }

}
