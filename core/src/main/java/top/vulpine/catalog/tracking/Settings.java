package top.vulpine.catalog.tracking;

import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;

import java.util.function.Supplier;

/**
 * What a plugin does on its own.
 */
public final class Settings {

    private final TrackingStore tracking;
    private final Supplier<TrackingDefaults> defaults;

    public Settings(TrackingStore tracking, Supplier<TrackingDefaults> defaults) {
        this.tracking = tracking;
        this.defaults = defaults;
    }

    /**
     * Changes which builds a plugin will accept from now on.
     *
     * @param plugin  the plugin to change
     * @param channel the least stable channel it should accept
     */
    public void channel(TrackedPlugin plugin, ReleaseChannel channel) throws TrackingException {
        plugin.channel(channel);
        tracking.save();
    }

    /**
     * Decides whether Catalog may update this plugin without being asked.
     *
     * @param plugin the plugin to change
     * @param on     true to let it update itself
     */
    public void autoUpdate(TrackedPlugin plugin, boolean on) throws TrackingException {
        plugin.autoUpdate(on);
        tracking.save();
    }

    /**
     * Sets how long a build must have been public before this plugin will take it unattended.
     *
     * @param plugin  the plugin to change
     * @param minutes the window, or {@link TrackedPlugin#INHERIT_SOAK} to follow the config
     */
    public void soak(TrackedPlugin plugin, int minutes) throws TrackingException {
        plugin.soakMinutes(minutes == TrackedPlugin.INHERIT_SOAK ? minutes : Math.max(minutes, 0));
        tracking.save();
    }

    /**
     * Freezes a plugin at the version it has now, or lets it move again.
     *
     * @param plugin the plugin to hold
     * @param held   true to freeze it
     */
    public void held(TrackedPlugin plugin, boolean held) throws TrackingException {

        if (held) {
            plugin.pinToCurrent();
        } else {
            plugin.pinnedVersionId(null);
        }

        tracking.save();
    }

    /**
     * @return the soak window plugins fall back to when they follow the config
     */
    public int defaultSoakMinutes() {
        return defaults.get().soakMinutes();
    }

}
