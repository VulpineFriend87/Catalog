package top.vulpine.catalog.velocity.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.velocity.actor.VelocityCommandActor;
import top.vulpine.catalog.Errors;
import top.vulpine.catalog.jar.model.InstalledJar;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.trash.TrashBin;
import top.vulpine.catalog.update.model.UpdateCandidate;
import top.vulpine.catalog.velocity.CatalogVelocity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A stand-in for the real {@code /catalog} on the proxy, for testing from the console.
 */
@Command({"catalog", "ctlg"})
public final class ConsoleCommand {

    private static final TextColor BRAND = TextColor.color(0xC08CFF);
    private static final TextColor PENDING = TextColor.color(0xF2C46B);
    private static final TextColor DANGER = TextColor.color(0xFF7B72);
    private static final NamedTextColor TEXT = NamedTextColor.WHITE;
    private static final NamedTextColor MUTED = NamedTextColor.GRAY;

    private final CatalogVelocity plugin;

    public ConsoleCommand(CatalogVelocity plugin) {
        this.plugin = plugin;
    }

    @Subcommand("list")
    @Description("Managed plugins")
    public void list(VelocityCommandActor actor) {
        async(() -> showList(actor));
    }

    @Subcommand("check")
    @Description("Check for updates now")
    public void check(VelocityCommandActor actor) {

        async(() -> {
            try {
                plugin.getUpdates().refresh();
            } catch (Exception e) {
                send(actor, Component.text("Could not reach Modrinth: " + Errors.rootMessage(e),
                        DANGER));
            }
            showList(actor);
        });
    }

    @Subcommand("update")
    @Description("Download updates, apply them on next restart")
    public void update(VelocityCommandActor actor, @Named("plugin") String query) {

        async(() -> {

            Map<String, UpdateCandidate> open = plugin.getUpdates().byProject();
            List<UpdateCandidate> chosen = new ArrayList<>();

            if (query.equalsIgnoreCase("all")) {
                chosen.addAll(open.values());
            } else {

                TrackedPlugin tracked = resolve(query);

                if (tracked == null) {
                    send(actor, Component.text("No plugin found for " + query, DANGER));
                    return;
                }

                UpdateCandidate candidate = open.get(tracked.projectId());

                if (candidate == null || tracked.awaitingRestart()) {
                    send(actor, Component.text(tracked.displayName() + " has no update", MUTED));
                    return;
                }

                chosen.add(candidate);
            }

            for (UpdateCandidate candidate : chosen) {

                if (candidate.plugin().awaitingRestart()) {
                    continue;
                }

                try {
                    plugin.getInstaller().stage(candidate, actor.name());
                    send(actor, Component.text(candidate.plugin().displayName() + " "
                            + candidate.to(), TEXT).append(Component.text(
                            " downloaded, applies on restart", MUTED)));
                } catch (Exception e) {
                    send(actor, Component.text("Could not update " + candidate.plugin().displayName()
                            + ": " + Errors.rootMessage(e), DANGER));
                }
            }
        });
    }

    @Subcommand("cancel")
    @Description("Drop a downloaded update")
    public void cancel(VelocityCommandActor actor, @Named("plugin") String query) {

        async(() -> {

            TrackedPlugin tracked = resolve(query);

            if (tracked == null) {
                send(actor, Component.text("No plugin found for " + query, DANGER));
                return;
            }

            try {
                boolean cancelled = plugin.getInstaller().cancel(tracked, actor.name());
                send(actor, Component.text(tracked.displayName(), TEXT).append(Component.text(
                        cancelled ? " update dropped" : " has no update to cancel", MUTED)));
            } catch (Exception e) {
                send(actor, Component.text(Errors.rootMessage(e), DANGER));
            }
        });
    }

    @Subcommand("uninstall")
    @Description("Move a plugin to trash")
    public void uninstall(VelocityCommandActor actor, @Named("plugin") String query) {

        async(() -> {

            TrackedPlugin tracked = resolve(query);

            if (tracked == null) {
                send(actor, Component.text("No plugin found for " + query, DANGER));
                return;
            }

            if (tracked.fileName().equals(plugin.ownFileName())) {
                send(actor, Component.text("Catalog cannot remove itself", DANGER));
                return;
            }

            try {
                plugin.getRemovals().cancelStagedFor(tracked);
                TrashBin.Result result = plugin.getRemovals().uninstall(tracked, actor.name());
                boolean deleted = result == null || result.deleted();
                send(actor, Component.text(tracked.displayName(), TEXT).append(Component.text(
                        deleted ? " moved to trash, unloads on restart"
                                : " moved to trash, the jar is removed on restart", MUTED)));
            } catch (Exception e) {
                send(actor, Component.text(Errors.rootMessage(e), DANGER));
            }
        });
    }

    private void showList(VelocityCommandActor actor) {

        Map<String, UpdateCandidate> open = plugin.getUpdates().byProject();
        List<TrackedPlugin> plugins = new ArrayList<>(plugin.getTracking().all());
        plugins.sort(Comparator.comparing(p -> p.displayName().toLowerCase(Locale.ROOT)));

        Component header = Component.text("Catalog", BRAND)
                .append(Component.text("  " + plugins.size(), TEXT))
                .append(Component.text(" plugins", MUTED));

        if (!open.isEmpty()) {
            header = header.append(Component.text(" · ", MUTED))
                    .append(Component.text(open.size(), BRAND))
                    .append(Component.text(" to update", MUTED));
        }

        send(actor, header);

        for (TrackedPlugin tracked : plugins) {

            Component row = Component.text("  " + tracked.displayName(), TEXT)
                    .append(Component.text("  " + tracked.versionNumber(), MUTED));

            UpdateCandidate candidate = open.get(tracked.projectId());

            if (tracked.awaitingRestart()) {
                row = row.append(Component.text("  restart", PENDING));
            } else if (candidate != null) {
                row = row.append(Component.text(" → ", MUTED))
                        .append(Component.text(candidate.to(), BRAND));
            } else if (tracked.isPinned()) {
                row = row.append(Component.text("  held", MUTED));
            }

            send(actor, row);
        }

        for (InstalledJar jar : plugin.getLibrary().untracked()) {
            send(actor, Component.text("  " + jar.fileName(), MUTED)
                    .append(Component.text("  untracked", MUTED)));
        }
    }

    private TrackedPlugin resolve(String query) {

        for (TrackedPlugin tracked : plugin.getTracking().all()) {
            if (query.equalsIgnoreCase(tracked.slug()) || query.equalsIgnoreCase(tracked.displayName())
                    || query.equals(tracked.projectId())) {
                return tracked;
            }
        }

        return null;
    }

    private void async(Runnable work) {
        plugin.getServer().getScheduler().buildTask(plugin, work).schedule();
    }

    private static void send(VelocityCommandActor actor, Component line) {
        actor.source().sendMessage(line);
    }

}
