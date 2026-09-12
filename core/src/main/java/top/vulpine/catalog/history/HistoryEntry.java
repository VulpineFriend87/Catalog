package top.vulpine.catalog.history;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.trash.model.TrashEntry;

import java.time.Instant;
import java.util.List;

/**
 * One thing Catalog did, or found had been done to it.
 *
 * <p>Serialized to {@code history.json}. A plain class bound by field name, like everything else
 * Gson reads here.</p>
 */
@Getter
@Builder
@Accessors(fluent = true)
public final class HistoryEntry {

    private Instant at;

    private Event event;

    /** Absent when the event is not about one plugin, as when the trash is emptied. */
    private String projectId;

    /** The name the plugin had when this happened, which is what the row shows. */
    private String name;

    private String slug;

    /** Absent when Catalog did it on its own, which is how the screen knows to say so. */
    private String by;

    private String from;

    private String to;

    private String channel;

    /** The new value, for the settings a number or a word rather than a version. */
    private String value;

    /** How many, when one row stands for several plugins. */
    private int count;

    /** The names behind {@link #count}, shown in the hover. */
    private List<String> names;

    /**
     * @return true if a person did this, rather than Catalog deciding on its own
     */
    public boolean byPerson() {
        return by != null && !by.isBlank();
    }

    private static HistoryEntryBuilder about(TrackedPlugin plugin, String by) {
        return builder()
                .at(Instant.now())
                .projectId(plugin.projectId())
                .name(plugin.displayName())
                .slug(plugin.slug())
                .by(by);
    }

    /**
     * @param requiredBy the plugin that pulled this one in, or null when it was asked for outright
     */
    public static HistoryEntry installed(TrackedPlugin plugin, String by, String requiredBy) {
        return about(plugin, by)
                .event(requiredBy == null ? Event.INSTALLED : Event.INSTALLED_AS_DEPENDENCY)
                .to(plugin.versionNumber())
                .channel(plugin.channel() == null ? null : plugin.channel().apiName())
                .value(requiredBy)
                .build();
    }

    public static HistoryEntry staged(TrackedPlugin plugin, ModrinthVersion version, String by,
                                      Event as) {
        return about(plugin, by)
                .event(as)
                .from(plugin.versionNumber())
                .to(version.versionNumber())
                .channel(version.versionType() == null ? null : version.versionType().apiName())
                .build();
    }

    public static HistoryEntry cancelled(TrackedPlugin plugin, String by) {
        return about(plugin, by)
                .event(Event.UPDATE_CANCELLED)
                .from(plugin.versionNumber())
                .build();
    }

    public static HistoryEntry trashed(TrackedPlugin plugin, String by) {
        return about(plugin, by)
                .event(Event.TRASHED)
                .from(plugin.versionNumber())
                .build();
    }

    public static HistoryEntry restored(TrackedPlugin plugin, String by) {
        return about(plugin, by)
                .event(Event.RESTORED)
                .to(plugin.versionNumber())
                .build();
    }

    public static HistoryEntry heldBack(TrackedPlugin plugin, ModrinthVersion version, int missing,
                                        List<String> names) {
        return about(plugin, null)
                .event(Event.UPDATE_HELD_BACK)
                .from(plugin.versionNumber())
                .to(version.versionNumber())
                .count(missing)
                .names(names)
                .build();
    }

    /**
     * A removal deleted for good, named from what the trash kept rather than from a tracked plugin.
     */
    public static HistoryEntry deleted(TrashEntry entry, String by) {
        return builder()
                .at(Instant.now())
                .event(Event.DELETED)
                .projectId(entry.projectId())
                .name(entry.displayName())
                .slug(entry.slug())
                .from(entry.versionNumber())
                .by(by)
                .build();
    }

    public static HistoryEntry setting(TrackedPlugin plugin, Event event, String value, String by) {
        return about(plugin, by).event(event).value(value).build();
    }

    /**
     * A staged build the restart put in place.
     *
     * <p>Nobody is credited: the restart applied it, and whoever queued it is already named on the
     * row that queued it.</p>
     */
    public static HistoryEntry applied(TrackedPlugin plugin) {
        return about(plugin, null)
                .event(Event.UPDATES_APPLIED)
                .to(plugin.versionNumber())
                .build();
    }

    public static HistoryEntry found(TrackedPlugin plugin, Event event) {
        return about(plugin, null).event(event).to(plugin.versionNumber()).build();
    }

    public static HistoryEntry many(Event event, int count, List<String> names, String by) {
        return builder()
                .at(Instant.now())
                .event(event)
                .count(count)
                .names(names)
                .by(by)
                .build();
    }

}
