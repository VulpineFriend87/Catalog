package top.vulpine.catalog.trash.model;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

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

    private String removedBy;
    private Instant removedAt;

}
