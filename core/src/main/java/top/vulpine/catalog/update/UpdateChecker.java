package top.vulpine.catalog.update;

import top.vulpine.catalog.modrinth.ModrinthClient;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.tracking.TrackingStore;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.update.model.ServerTarget;
import top.vulpine.catalog.update.model.UpdateCandidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks Modrinth what is out of date.
 *
 * <p>Plugins are grouped by the channel they follow and each group is one request, so a whole
 * server is answered in at most three no matter how many plugins are installed.</p>
 */
public final class UpdateChecker {

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

        for (TrackedPlugin plugin : plugins) {
            byHash.put(plugin.sha512(), plugin);
        }

        Map<String, ModrinthVersion> latest = askByTier(byHash.keySet(), channel, target);

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
     * Asks for each loader group in turn, narrowing to the plugins still unanswered.
     */
    private Map<String, ModrinthVersion> askByTier(Collection<String> hashes, ReleaseChannel channel,
                                                   ServerTarget target) {

        Map<String, ModrinthVersion> answers = new HashMap<>();
        List<String> pending = new ArrayList<>(hashes);

        for (List<String> tier : target.platform().loaderTiers()) {

            if (pending.isEmpty()) {
                break;
            }

            Map<String, ModrinthVersion> found = lookup.latest(
                    pending,
                    tier,
                    target.gameVersions(),
                    List.of(channel.included())
            );

            answers.putAll(found);
            pending.removeAll(found.keySet());
        }

        return answers;
    }

    /**
     * Whether a version Modrinth offered is an upgrade.
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
