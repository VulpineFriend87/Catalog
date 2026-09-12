package top.vulpine.catalog.tracking;

import top.vulpine.catalog.history.Event;
import top.vulpine.catalog.history.History;
import top.vulpine.catalog.history.HistoryEntry;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;

import java.util.function.Supplier;

/**
 * The settings of a tracked plugin.
 */
public final class Settings {

    private final TrackingStore tracking;
    private final Supplier<TrackingDefaults> defaults;
    private final History history;

    public Settings(TrackingStore tracking, Supplier<TrackingDefaults> defaults, History history) {
        this.tracking = tracking;
        this.defaults = defaults;
        this.history = history;
    }

    /**
     * Changes which builds a plugin will accept from now on.
     *
     * @param plugin  the plugin to change
     * @param channel the least stable channel it should accept
     * @param by      who asked
     */
    public void channel(TrackedPlugin plugin, ReleaseChannel channel, String by)
            throws TrackingException {
        plugin.channel(channel);
        tracking.save();
        history.add(HistoryEntry.setting(plugin, Event.CHANNEL, channel.apiName(), by));
    }

    /**
     * Decides whether Catalog may update this plugin automatically.
     *
     * @param plugin the plugin to change
     * @param on     true to let it update itself
     * @param by     who asked
     */
    public void autoUpdate(TrackedPlugin plugin, boolean on, String by)
            throws TrackingException {
        plugin.autoUpdate(on);
        tracking.save();
        history.add(HistoryEntry.setting(plugin, Event.AUTO_UPDATE, on ? "on" : "off", by));
    }

    /**
     * Sets how long a build must have been public before this plugin installs it
     * automatically.
     *
     * @param plugin  the plugin to change
     * @param minutes the window, or {@link TrackedPlugin#INHERIT_SOAK} to follow the config
     * @param by      who asked
     */
    public void soak(TrackedPlugin plugin, int minutes, String by) throws TrackingException {
        plugin.soakMinutes(minutes == TrackedPlugin.INHERIT_SOAK ? minutes : Math.max(minutes, 0));
        tracking.save();
        // The word rather than the number: -1 means follow the config, and rendering it as a
        // duration would say "none", which is the opposite.
        history.add(HistoryEntry.setting(plugin, Event.SOAK,
                plugin.soakMinutes() == TrackedPlugin.INHERIT_SOAK
                        ? "default" : String.valueOf(plugin.soakMinutes()), by));
    }

    /**
     * Freezes a plugin at the version it has now, or lets it move again.
     *
     * @param plugin the plugin to hold
     * @param held   true to freeze it
     * @param by     who asked
     */
    public void held(TrackedPlugin plugin, boolean held, String by) throws TrackingException {

        if (held) {
            plugin.pinToCurrent();
        } else {
            plugin.pinnedVersionId(null);
        }

        tracking.save();
        history.add(HistoryEntry.setting(plugin, held ? Event.HELD : Event.UNHELD, null, by));
    }

    /**
     * @return the soak window plugins fall back to when they follow the config
     */
    public int defaultSoakMinutes() {
        return defaults.get().soakMinutes();
    }

}
