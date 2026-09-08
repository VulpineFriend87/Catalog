package top.vulpine.catalog.paper.command;

import org.bukkit.command.CommandSender;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Named;

/**
 * The command every button in chat actually runs.
 *
 * <p>Registered through Lamp like the rest of the command surface, so the client is given it in its
 * copy of the command tree and sends it without asking whether an unrecognised command was really
 * meant. Its one argument is last and not single, so it is greedy and swallows the screen and the
 * real command whole.</p>
 *
 * <p>It carries no permission of its own. What it dispatches runs as whoever pressed the button and
 * is checked like any other command, so this can never be a way around one.</p>
 */
public final class ClickCommand {

    private final ClickContext context;

    public ClickCommand(ClickContext context) {
        this.context = context;
    }

    @Command("catalog-do")
    public void press(CommandSender sender, @Named("press") String press) {
        context.press(sender, press);
    }

}
