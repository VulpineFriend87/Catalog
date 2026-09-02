package top.vulpine.catalog.paper.util;

import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * Checks Catalog permission nodes, honouring wildcards and the admin node.
 */
public final class PermissionChecker {

    private static final String ROOT = "catalog";

    private PermissionChecker() {
    }

    /**
     * @param sender the sender to check
     * @param base   the node below {@code catalog.}, e.g. {@code command.list}
     * @return true if the sender holds it, or a wildcard above it
     */
    public static boolean hasPermission(CommandSender sender, String base) {

        if (sender.hasPermission(ROOT + ".admin") || sender.hasPermission(ROOT + ".*")) {
            return true;
        }

        String node = ROOT + "." + base.toLowerCase(java.util.Locale.ROOT);

        if (sender.hasPermission(node) || sender.hasPermission(node + ".*")) {
            return true;
        }

        String prefix = node + ".";

        for (PermissionAttachmentInfo granted : sender.getEffectivePermissions()) {
            if (granted.getValue() && granted.getPermission().startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

}
