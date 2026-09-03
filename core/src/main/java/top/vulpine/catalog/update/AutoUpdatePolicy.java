package top.vulpine.catalog.update;

import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.update.model.UpdateCandidate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Decides which available updates Catalog is allowed to install without being asked.
 *
 * <p>Two gates, and a candidate has to pass both. The plugin must have {@code auto_update} turned
 * on, which is off by default and always a deliberate choice. And the build must have been public
 * long enough to have soaked — the window exists because the release an author hotfixes twenty
 * minutes later is the one nobody wants to have installed automatically.</p>
 *
 * <p>Nothing here touches the disk or the network: it answers a question, and the caller decides
 * what to do with the answer. That is what makes the rule testable without a server.</p>
 */
public final class AutoUpdatePolicy {

    private final int defaultSoakMinutes;

    /**
     * @param defaultSoakMinutes the soak window for plugins that inherit it from the config
     */
    public AutoUpdatePolicy(int defaultSoakMinutes) {
        this.defaultSoakMinutes = Math.max(defaultSoakMinutes, 0);
    }

    /**
     * The updates that should be applied right now, without asking.
     *
     * @param candidates what the check found
     * @param now        the moment to judge the soak window against
     * @return the candidates to stage, in the order given
     */
    public List<UpdateCandidate> readyToApply(Collection<UpdateCandidate> candidates, Instant now) {

        List<UpdateCandidate> ready = new ArrayList<>();

        for (UpdateCandidate candidate : candidates) {
            if (wanted(candidate) && !soaking(candidate, now)) {
                ready.add(candidate);
            }
        }

        return ready;
    }

    /**
     * Whether this plugin has asked to be updated on its own.
     *
     * <p>A build already staged is not wanted again: it is waiting for a restart, and downloading
     * over it would only replace one pending change with another.</p>
     */
    private static boolean wanted(UpdateCandidate candidate) {

        TrackedPlugin plugin = candidate.plugin();
        return plugin.autoUpdate() && !plugin.awaitingRestart() && !plugin.isPinned();
    }

    /**
     * Whether a build is still too new to install unattended.
     *
     * @param candidate the update on offer
     * @param now       the moment to judge against
     * @return true if the soak window has not elapsed yet
     */
    public boolean soaking(UpdateCandidate candidate, Instant now) {

        Instant published = candidate.version().datePublished();
        int minutes = soakMinutes(candidate.plugin());

        if (published == null || minutes <= 0) {
            return false;
        }

        return published.plus(Duration.ofMinutes(minutes)).isAfter(now);
    }

    /**
     * How long this plugin waits, which is its own setting unless it defers to the config.
     *
     * @param plugin the plugin to ask about
     * @return the soak window in minutes
     */
    public int soakMinutes(TrackedPlugin plugin) {
        return plugin.soakMinutes() == TrackedPlugin.INHERIT_SOAK
                ? defaultSoakMinutes
                : Math.max(plugin.soakMinutes(), 0);
    }

}
