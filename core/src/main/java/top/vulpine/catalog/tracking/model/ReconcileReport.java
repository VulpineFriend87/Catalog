package top.vulpine.catalog.tracking.model;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import top.vulpine.catalog.jar.model.InstalledJar;

import java.util.Collections;
import java.util.List;

/**
 * What one pass of reconciliation found, split by what the operator has to know about each case.
 *
 * <p>Every list is separate because they call for different reactions: an adoption is routine and
 * belongs in a one-line summary, an orphaned plugin is a surprise and deserves a warning.</p>
 */
@Getter
@Builder
@Accessors(fluent = true)
public final class ReconcileReport {

    /** Recognised on Modrinth and now tracked, having been left alone until this point. */
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
     * <p>The server did not take the file from the update folder. Worth saying out loud: the
     * operator restarted expecting a new version and has the old one, and nothing else would tell
     * them so.</p>
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
     * The file was replaced by something Catalog can no longer tie to the same project, so tracking
     * stopped rather than carry on pointing at the wrong thing.
     */
    @Builder.Default
    private final List<TrackedPlugin> orphaned = Collections.emptyList();

    /** Not on Modrinth. Listed, never touched. */
    @Builder.Default
    private final List<InstalledJar> unknown = Collections.emptyList();

    /** Skipped because the operator put it on the ignore list. */
    @Builder.Default
    private final List<InstalledJar> ignored = Collections.emptyList();

    /**
     * A second jar for a project that is already tracked.
     *
     * <p>Caught by project id, which comes from the hash and so cannot be wrong. Comparing the
     * plugin name a jar declares would be easier and is a trap: a jar that shades a library
     * shipping its own descriptor can declare a name that has nothing to do with what it is.</p>
     */
    @Builder.Default
    private final List<InstalledJar> conflicting = Collections.emptyList();

    /**
     * Recognised on Modrinth but left untracked because auto-tracking is off.
     *
     * <p>Distinct from {@link #unknown()}: Catalog knows perfectly well what these are, and is
     * standing back because it was told to.</p>
     */
    @Builder.Default
    private final List<InstalledJar> notAdopted = Collections.emptyList();

    /**
     * Whether anything at all changed, so a quiet startup can stay quiet.
     *
     * @return true if tracking state was modified
     */
    public boolean hasChanges() {
        return !adopted.isEmpty() || !moved.isEmpty() || !renamed.isEmpty()
                || !removed.isEmpty() || !orphaned.isEmpty() || !applied.isEmpty();
    }

    /**
     * Whether anything happened that the operator should look at rather than merely be told about.
     *
     * @return true if there is something worth a warning
     */
    public boolean needsAttention() {
        return !orphaned.isEmpty() || !conflicting.isEmpty() || !notApplied.isEmpty();
    }

}
