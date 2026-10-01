package top.vulpine.catalog.paper.notify;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import top.vulpine.catalog.paper.CatalogPaper;
import top.vulpine.catalog.paper.command.Messages;
import top.vulpine.catalog.paper.command.Notice;
import top.vulpine.catalog.paper.util.PermissionChecker;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.update.model.UpdateCandidate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tells staff about updates when they join.
 *
 * <p>Open updates are repeated on every join. What the last start did is told once per player.</p>
 */
public final class JoinNotifier implements Listener {

    private static final Duration DELAY = Duration.ofSeconds(3);

    /** How many times to wait for the first update check before giving up on this join. */
    private static final int ATTEMPTS = 10;

    private final CatalogPaper plugin;

    /**
     * The news each player has already heard this session, by plugin and build.
     */
    private final Map<UUID, Set<String>> heard = new ConcurrentHashMap<>();

    public JoinNotifier(CatalogPaper plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {

        Player player = event.getPlayer();

        if (!plugin.getConfiguration().updates.notifyOnJoin
                || !PermissionChecker.hasPermission(player, "notify")) {
            return;
        }

        later(player, ATTEMPTS);
    }

    private void later(Player player, int attempts) {
        plugin.getScheduler().runAtEntityLater(player, () -> tell(player, attempts),
                DELAY.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void tell(Player player, int attempts) {

        if (!player.isOnline()) {
            return;
        }

        // No answer before the first check.
        if (plugin.getUpdates().checkedAt() == null) {

            if (attempts > 1) {
                later(player, attempts - 1);
            }

            return;
        }

        Set<String> heardBefore = heard.computeIfAbsent(player.getUniqueId(),
                uuid -> ConcurrentHashMap.newKeySet());
        Set<String> news = new HashSet<>();

        List<Notice> notices = notices(heardBefore, news);

        if (notices.isEmpty()) {
            return;
        }

        player.sendMessage(Component.empty());

        for (Component line : Messages.notice(notices, hasAvailable(notices))) {
            player.sendMessage(line);
        }

        heardBefore.addAll(news);
    }

    /**
     * @param heardBefore the news this player has already been told
     * @param news        filled with the news this call reports
     */
    private List<Notice> notices(Set<String> heardBefore, Set<String> news) {

        List<Notice> out = new ArrayList<>();

        Set<TrackedPlugin> missed = new HashSet<>();
        List<Notice.Item> missedNews = new ArrayList<>();

        for (TrackedPlugin tracked : plugin.getLibrary().notApplied()) {

            if (!tracked.pendingRestart()) {
                continue;
            }

            missed.add(tracked);

            String key = "missed:" + tracked.projectId() + ":" + tracked.stagedVersionId();

            if (!heardBefore.contains(key)) {
                news.add(key);
                missedNews.add(new Notice.Item(tracked.displayName(),
                        tracked.versionNumber() + " is still installed"));
            }
        }

        add(out, Notice.Kind.DID_NOT_APPLY, missedNews);

        Map<String, String> failures = plugin.getUpdates().failures();
        List<Notice.Item> autoFailed = new ArrayList<>();
        List<Notice.Item> available = new ArrayList<>();

        for (UpdateCandidate candidate : plugin.updates()) {

            String builds = candidate.from() + " → " + candidate.to();
            String reason = failures.get(candidate.plugin().projectId());

            (reason == null ? available : autoFailed).add(
                    new Notice.Item(candidate.plugin().displayName(), builds, reason));
        }

        add(out, Notice.Kind.AUTO_UPDATE_FAILED, autoFailed);

        List<Notice.Item> applied = new ArrayList<>();

        for (TrackedPlugin tracked : plugin.getLibrary().applied()) {

            String key = "applied:" + tracked.projectId() + ":" + tracked.versionId();

            if (!heardBefore.contains(key)) {
                news.add(key);
                applied.add(new Notice.Item(tracked.displayName(), tracked.versionNumber()));
            }
        }

        add(out, Notice.Kind.APPLIED, applied);

        List<TrackedPlugin> waiting = new ArrayList<>();

        for (TrackedPlugin tracked : plugin.getTracking().all()) {
            if (tracked.awaitingRestart() && !missed.contains(tracked)) {
                waiting.add(tracked);
            }
        }

        add(out, Notice.Kind.WAITING, names(waiting));
        add(out, Notice.Kind.AVAILABLE, available);

        return out;
    }

    private static void add(List<Notice> out, Notice.Kind kind, List<Notice.Item> plugins) {

        if (plugins.isEmpty()) {
            return;
        }

        List<Notice.Item> sorted = new ArrayList<>(plugins);
        sorted.sort(Comparator.comparing(item -> item.name().toLowerCase(Locale.ROOT)));
        out.add(new Notice(kind, sorted));
    }

    private static List<Notice.Item> names(Iterable<TrackedPlugin> plugins) {

        List<Notice.Item> names = new ArrayList<>();

        for (TrackedPlugin tracked : plugins) {
            names.add(new Notice.Item(tracked.displayName(), null));
        }

        return names;
    }

    private static boolean hasAvailable(List<Notice> notices) {

        for (Notice notice : notices) {
            if (notice.kind() == Notice.Kind.AVAILABLE) {
                return true;
            }
        }

        return false;
    }

}
