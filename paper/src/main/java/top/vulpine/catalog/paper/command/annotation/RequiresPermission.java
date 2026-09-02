package top.vulpine.catalog.paper.command.annotation;

import revxrsal.commands.annotation.DistributeOnMethods;
import revxrsal.commands.annotation.NotSender;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a command as requiring a Catalog permission node.
 *
 * <p>The value is relative to the plugin root, so {@code command.list} resolves to
 * {@code catalog.command.list} and is checked through
 * {@link top.vulpine.catalog.paper.util.PermissionChecker}, which keeps wildcard and admin nodes
 * working.</p>
 */
@DistributeOnMethods
@NotSender.ImpliesNotSender
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /**
     * @return the node, relative to {@code catalog}
     */
    String value();

}
