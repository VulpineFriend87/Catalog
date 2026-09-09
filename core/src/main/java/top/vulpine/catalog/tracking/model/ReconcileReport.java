package top.vulpine.catalog.tracking.model;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import top.vulpine.catalog.jar.model.InstalledJar;

import java.util.Collections;
import java.util.List;

/**
 * What one pass of reconciliation found.
 */
@Getter
@Builder
@Accessors(fluent = true)
public final class ReconcileReport {

    /** Recognized on Modrinth and now tracked, having been left alone until this point. */
    @Builder.Default
    private final List<TrackedPlugin> adopted = Collections.emptyList();

    /** The jar was replaced by hand with another version of the same project. */
    @Builder.Default
    private final List<TrackedPlugin> moved = Collections.emptyList();

    /** An update Catalog staged is now the jar on disk, so the restart that applies it has happened. */
    @Builder.Default
    private final List<TrackedPlugin> applied = Collections.emptyList();

    /**
     * An update Catalog staged is still not the jar on disk after a restart.
     *
     * <p>The server did not take the file from the update folder.</p>
     */
    @Builder.Default
    private final List<TrackedPlugin> notApplied = Collections.emptyList();

    /** Same file contents under a new name, so only the recorded file name changed. */
    @Builder.Default
    private final List<TrackedPlugin> renamed = Collections.emptyList();

    /** The file is gone, so tracking stopped. Deleting a jar by hand is a valid way to uninstall. */
    @Builder.Default
    private final List<TrackedPlugin> removed = Collections.emptyList();

    /**
     * The file was replaced by something Catalog can no longer tie to the same project, so tracking stopped.
     */
    @Builder.Default
    private final List<TrackedPlugin> orphaned = Collections.emptyList();

    /** Not on Modrinth. Listed, never touched. */
    @Builder.Default
    private final List<InstalledJar> unknown = Collections.emptyList();

    /** Skipped because put in the ignore list. */
    @Builder.Default
    private final List<InstalledJar> ignored = Collections.emptyList();

    /**
     * A second jar for a project that is already tracked.
     */
    @Builder.Default
    private final List<InstalledJar> conflicting = Collections.emptyList();

    /**
     * Recognized on Modrinth but left untracked because auto-tracking is off.
     *
     * <p>Distinct from {@link #unknown()}: Catalog knows perfectly well what these are, and is
     * standing back because it was told to.</p>
     */
    @Builder.Default
    private final List<InstalledJar> notAdopted = Collections.emptyList();

    /**
     * Whether anything at all changed.
     *
     * @return true if tracking state was modified
     */
    public boolean hasChanges() {
        return !adopted.isEmpty() || !moved.isEmpty() || !renamed.isEmpty()
                || !removed.isEmpty() || !orphaned.isEmpty() || !applied.isEmpty();
    }

    /**
     * Whether anything happened that someone should look at.
     *
     * @return true if there is something worth a warning
     */
    public boolean needsAttention() {
        return !orphaned.isEmpty() || !conflicting.isEmpty() || !notApplied.isEmpty();
    }

}
