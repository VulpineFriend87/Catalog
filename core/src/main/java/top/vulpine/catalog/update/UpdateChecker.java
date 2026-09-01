package top.vulpine.catalog.update;

import top.vulpine.catalog.modrinth.ModrinthClient;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.update.model.ServerTarget;
import top.vulpine.catalog.update.model.UpdateCandidate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks Modrinth what, if anything, is out of date.
 *
 * <p>Plugins are grouped by the channel they follow and each group costs one request, so a whole
 * server is answered in at most three no matter how many plugins are installed. Modrinth applies
 * the game version, loader and channel filters itself, which is what makes the answer trustworthy:
 * Catalog never decides compatibility from a version string.</p>
 */
public final class UpdateChecker {

    /**
     * The one question this checker asks of Modrinth.
     *
     * <p>Narrowed to an interface so every rule below — grouping by channel, skipping pins, deciding
     * what counts as newer — can be tested without a network.</p>
     */
    @FunctionalInterface
    public interface Lookup {

        /**
         * @param hashes       the SHA-512 hashes of what is installed
         * @param loaders      the loaders this server accepts
         * @param gameVersions the Minecraft versions this server accepts
         * @param channels     the release channels to consider
         * @return hash to the newest matching version, absent where there is none
         */
        Map<String, ModrinthVersion> latest(List<String> hashes, List<String> loaders,
                                            List<String> gameVersions, List<ReleaseChannel> channels);
    }

    private final Lookup lookup;
    private final TrackingStore store;

    public UpdateChecker(ModrinthClient modrinth, TrackingStore store) {
        this((hashes, loaders, gameVersions, channels) ->
                modrinth.latest(hashes, loaders, gameVersions, channels).join(), store);
    }

    public UpdateChecker(Lookup lookup, TrackingStore store) {
        this.lookup = lookup;
        this.store = store;
    }

    /**
     * Finds every tracked plugin with a newer version available for this server.
     *
     * <p>Blocks on the lookups, so it must be called off the main thread.</p>
     *
     * @param target what this server is
     * @return the available updates, in the order the plugins are tracked
     */
    public List<UpdateCandidate> check(ServerTarget target) {

        Map<ReleaseChannel, List<TrackedPlugin>> byChannel = groupByChannel();
        List<UpdateCandidate> candidates = new ArrayList<>();

        for (Map.Entry<ReleaseChannel, List<TrackedPlugin>> group : byChannel.entrySet()) {
            candidates.addAll(checkGroup(group.getKey(), group.getValue(), target));
        }

        return candidates;
    }

    /**
     * Splits the tracked plugins by channel, skipping the ones that cannot produce an update.
     *
     * <p>A pinned plugin is left out entirely rather than checked and discarded: the operator froze
     * it deliberately, and asking about it would only cost a slot in a request.</p>
     */
    private Map<ReleaseChannel, List<TrackedPlugin>> groupByChannel() {

        Map<ReleaseChannel, List<TrackedPlugin>> byChannel = new EnumMap<>(ReleaseChannel.class);

        for (TrackedPlugin plugin : store.all()) {

            if (plugin.isPinned() || plugin.sha512() == null) {
                continue;
            }

            byChannel.computeIfAbsent(plugin.channel(), key -> new ArrayList<>()).add(plugin);
        }

        return byChannel;
    }

    private List<UpdateCandidate> checkGroup(ReleaseChannel channel, List<TrackedPlugin> plugins,
                                             ServerTarget target) {

        Map<String, TrackedPlugin> byHash = new HashMap<>();
        List<String> hashes = new ArrayList<>();

        for (TrackedPlugin plugin : plugins) {
            byHash.put(plugin.sha512(), plugin);
            hashes.add(plugin.sha512());
        }

        Map<String, ModrinthVersion> latest = lookup.latest(
                hashes,
                target.loaders(),
                List.of(target.gameVersion()),
                List.of(channel.included())
        );

        List<UpdateCandidate> candidates = new ArrayList<>();

        for (Map.Entry<String, ModrinthVersion> found : latest.entrySet()) {

            TrackedPlugin plugin = byHash.get(found.getKey());
            ModrinthVersion version = found.getValue();

            if (plugin == null || !isNewer(plugin, version)) {
                continue;
            }

            candidates.add(UpdateCandidate.builder()
                    .plugin(plugin)
                    .version(version)
                    .declaresPlatform(target.platform().declaredBy(version.loaders()))
                    .build());
        }

        return candidates;
    }

    /**
     * Whether a version Modrinth offered is genuinely an upgrade.
     *
     * <p>Identity is the version id and order is the publish date. Version numbers are display
     * strings chosen by authors and are never compared — that is the mistake which produces updates
     * that do not exist.</p>
     *
     * <p>The date check is not redundant with the id check. Narrowing to one game version can make
     * the newest <em>compatible</em> build older than what is installed: a server on an older
     * Minecraft version whose plugin was updated by hand would otherwise be offered a downgrade.</p>
     */
    private static boolean isNewer(TrackedPlugin plugin, ModrinthVersion version) {

        if (version.id() == null || version.id().equals(plugin.versionId())) {
            return false;
        }

        if (plugin.datePublished() == null || version.datePublished() == null) {
            return true;
        }

        return version.datePublished().isAfter(plugin.datePublished());
    }

}
