package top.vulpine.catalog.tracking.model;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import top.vulpine.catalog.jar.model.InstalledJar;
import top.vulpine.catalog.jar.model.ScanResult;

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
     * <p>Caught by project id, which finds pairs that {@link ScanResult#duplicates()} cannot: that
     * check compares the plugin name a jar declares, and a jar with no readable descriptor declares
     * nothing.</p>
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

    /** Jars declaring the same plugin name, carried through from the scan. */
    @Builder.Default
    private final List<ScanResult.Duplicate> duplicates = Collections.emptyList();

    /**
     * Whether anything at all changed, so a quiet startup can stay quiet.
     *
     * @return true if tracking state was modified
     */
    public boolean hasChanges() {
        return !adopted.isEmpty() || !moved.isEmpty() || !renamed.isEmpty()
                || !removed.isEmpty() || !orphaned.isEmpty();
    }

    /**
     * Whether anything happened that the operator should look at rather than merely be told about.
     *
     * @return true if there is something worth a warning
     */
    public boolean needsAttention() {
        return !orphaned.isEmpty() || !conflicting.isEmpty() || !duplicates.isEmpty();
    }

}
