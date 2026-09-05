package top.vulpine.catalog.paper.command;

import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carries which screen a button was clicked on, without it ever being a command argument.
 *
 * <p>The buttons append {@code --data <something>} to the command they run. This listener takes it
 * off the command line before the command is dispatched, so Lamp parses a perfectly ordinary
 * command and nothing about it can reach tab completion. That matters because a real flag cannot be
 * hidden: Lamp always suggests a flag's name, and the Bukkit integration publishes every command
 * node to Brigadier without checking whether it is secret.</p>
 *
 * <p>The payload is free text, read one time by whoever asks for it next. A command typed by hand
 * carries none, which is exactly right — there is no screen open to put back.</p>
 *
 * <p><strong>Only a command whose last argument is greedy may be given a payload.</strong> The
 * client parses a clickable command against its own copy of the command tree before running it, and
 * Lamp maps a non-greedy parameter to {@code StringArgumentType.string()}, which stops at the first
 * space. Anything trailing then fails to parse and the player is asked to confirm running an
 * "unrecognized or invalid command" instead. A greedy last argument swallows the payload harmlessly,
 * and this listener has already removed it by the time the command actually runs.</p>
 */
public final class ClickContext implements Listener {

    /** What the buttons append. Anything after it is the payload. */
    public static final String MARKER = "--data ";

    /** The screen showing every managed plugin. */
    public static final String LIST = "list";

    /** The screen showing what has been removed. */
    public static final String TRASH = "trash";

    /** One project's page, followed by its slug. */
    public static final String INFO = "info:";

    /** One plugin's settings, followed by its slug. */
    public static final String SETTINGS = "settings:";

    /**
     * Marks a payload as coming from a confirmation button, wrapping the screen to return to.
     *
     * <p>Confirming has to be a different click from asking. Without this the confirmation was
     * "run the same command twice", which meant pressing a remove or version button twice in a row
     * carried the action out without the dialog ever being read.</p>
     */
    public static final String CONFIRM = "confirm:";

    private final Map<String, String> pending = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {

        String cleaned = capture(event.getPlayer(), event.getMessage());

        if (cleaned != null) {
            event.setMessage(cleaned);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsoleCommand(ServerCommandEvent event) {

        String cleaned = capture(event.getSender(), "/" + event.getCommand());

        if (cleaned != null) {
            event.setCommand(cleaned.substring(1));
        }
    }

    /**
     * @return the command without its payload, or null if there was nothing to take
     */
    private String capture(CommandSender sender, String message) {

        if (!isCatalog(message)) {
            return null;
        }

        int marker = message.indexOf(MARKER);

        if (marker < 0) {
            return null;
        }

        pending.put(sender.getName(), message.substring(marker + MARKER.length()).trim());
        return message.substring(0, marker).trim();
    }

    private static boolean isCatalog(String message) {

        String lower = message.toLowerCase(Locale.ROOT);
        return lower.startsWith("/catalog ") || lower.startsWith("/ctlg ");
    }

    /**
     * Reads and forgets the payload for a sender.
     *
     * @param sender who ran the command
     * @return what the button said, or null if the command was typed
     */
    public String take(CommandSender sender) {
        return pending.remove(sender.getName());
    }

    /**
     * Removes a payload that survived into an argument.
     *
     * <p>Only reachable if the command never passed through the events above — dispatched through
     * the API, say. The payload is lost, which costs a redraw; the argument staying intact is what
     * matters.</p>
     *
     * @param argument the raw argument
     * @return the argument without any trailing payload
     */
    public static String strip(String argument) {

        int marker = argument.indexOf(MARKER);
        return marker < 0 ? argument : argument.substring(0, marker).trim();
    }

}
