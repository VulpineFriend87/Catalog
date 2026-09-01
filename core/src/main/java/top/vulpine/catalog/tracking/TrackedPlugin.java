package top.vulpine.catalog.tracking;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One plugin Catalog manages, and everything it remembers about it.
 *
 * <p>The settings are independent switches rather than a single mode, because they answer different
 * questions: {@link #autoUpdate()} decides whether Catalog acts on its own, {@link #channel()}
 * decides which builds it will even consider, and {@link #pinnedVersionId()} freezes it outright.
 * Notification is not here at all — that is a server-wide feature, not a property of a plugin.</p>
 *
 * <p>Serialised to {@code tracked.json}. Losing that file is recoverable: hashes re-identify every
 * jar from scratch. What it would lose is the settings below, which is why the store writes it
 * atomically.</p>
 */
@Getter
@Setter
@Accessors(fluent = true)
public final class TrackedPlugin {

    /** Sentinel for {@link #soakMinutes()} meaning "use the value from config". */
    public static final int INHERIT_SOAK = -1;

    private String projectId;
    private String slug;
    private String name;

    private String versionId;
    private String versionNumber;
    private Instant datePublished;

    /** The file in {@code plugins/} as it is named right now, canonical once adopted. */
    private String fileName;

    /** The canonical name still to be applied, when the rename had to wait for shutdown. */
    private String pendingRenameTo;

    /** Set once an update is staged, cleared after the restart that applied it is verified. */
    private boolean pendingRestart;

    private String sha512;

    private ReleaseChannel channel = ReleaseChannel.RELEASE;

    /**
     * Off by default, deliberately. Catalog adopts a whole plugins folder on first boot; adopting
     * and then queueing forty unrequested updates would be the intrusiveness this project exists to
     * avoid.
     */
    private boolean autoUpdate;

    private int soakMinutes = INHERIT_SOAK;

    /** Non-null freezes this plugin to exactly that version, against manual updates too. */
    private String pinnedVersionId;

    /** File name to destination directory, for versions that ship more than the plugin jar. */
    private Map<String, String> extraFiles = new LinkedHashMap<>();

    private String installedBy;
    private Instant installedAt;

    /**
     * False when this was pulled in to satisfy someone else's requirement, which is what lets
     * {@code autoremove} offer it once nothing needs it any more.
     */
    private boolean explicit = true;

    /**
     * Starts tracking a version that has just been identified or installed.
     *
     * @param version   the version the file on disk turned out to be
     * @param fileName  the name of that file in the plugins folder
     * @param sha512    its hash
     * @param channel   the channel this plugin should follow from now on
     * @param installedBy who to record in the audit log
     * @return a new record with the defaults applied
     */
    public static TrackedPlugin of(ModrinthVersion version, String fileName, String sha512,
                                   ReleaseChannel channel, String installedBy) {

        TrackedPlugin tracked = new TrackedPlugin();

        tracked.projectId = version.projectId();
        tracked.versionId = version.id();
        tracked.versionNumber = version.versionNumber();
        tracked.datePublished = version.datePublished();
        tracked.fileName = fileName;
        tracked.sha512 = sha512;
        tracked.channel = channel;
        tracked.installedBy = installedBy;
        tracked.installedAt = Instant.now();

        return tracked;
    }

    /**
     * Records that this plugin is now a different version of the same project.
     *
     * @param version the version now on disk
     * @param fileName the file it lives in
     * @param sha512  its hash
     */
    public void moveTo(ModrinthVersion version, String fileName, String sha512) {
        this.versionId = version.id();
        this.versionNumber = version.versionNumber();
        this.datePublished = version.datePublished();
        this.fileName = fileName;
        this.sha512 = sha512;
    }

    /**
     * Whether this plugin is frozen to a specific version.
     *
     * @return true if a pin is set
     */
    public boolean isPinned() {
        return pinnedVersionId != null;
    }

    /**
     * Freezes this plugin to whatever is installed right now.
     *
     * <p>Resolved to the concrete version immediately rather than stored as "current", so the pin
     * cannot quietly drift if the file is replaced by hand.</p>
     */
    public void pinToCurrent() {
        this.pinnedVersionId = versionId;
    }

    /**
     * The display name for messages, falling back through what is known.
     *
     * @return the friendliest available name
     */
    public String displayName() {

        if (name != null && !name.isBlank()) {
            return name;
        }

        if (slug != null && !slug.isBlank()) {
            return slug;
        }

        return projectId;
    }

    @Override
    public String toString() {
        return "TrackedPlugin(" + displayName() + " " + versionNumber + ", " + channel + ")";
    }

}
