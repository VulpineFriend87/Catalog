package top.vulpine.catalog.paper.command;

import org.bukkit.Bukkit;
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
 * Carries which screen a button was pressed on, without it ever being part of a real command.
 *
 * <p>Buttons do not run the command they mean. They run {@code /catalog-do <screen> <command>},
 * which remembers the screen and then runs the command itself. Nothing about the screen reaches
 * Lamp or the arguments of any real command.</p>
 *
 * <p>That is the whole point. The commands themselves are then free to be shaped however reads
 * best — flags, switches, single arguments — instead of every one that a button can reach being
 * forced to end in a greedy string so a payload had somewhere to hide.</p>
 *
 * <p>Only Catalog's own commands are dispatched, and they run as whoever pressed the button, with
 * their permissions. A player typing the wrapper by hand can therefore do nothing they could not do
 * by typing the command directly.</p>
 *
 * <p>{@link ClickCommand} registers it, so the client is given it and sends it without asking
 * whether an unrecognised command was really meant. The listener below stays for the console and
 * for anything that reaches the server without passing through Lamp.</p>
 */
public final class ClickContext implements Listener {

    /** What every button actually runs. */
    public static final String CLICK = "/catalog-do ";

    /** Stands in for the screen when a button was not offered from one. */
    private static final String NOWHERE = "-";

    /** The screen showing every managed plugin. */
    public static final String LIST = "list";

    /** The screen showing what has been removed. */
    public static final String TRASH = "trash";

    /** One project's page, followed by its slug. */
    public static final String INFO = "info:";

    /** One project's settings, followed by its slug. */
    public static final String SETTINGS = "settings:";

    /** One project's dependencies, followed by its slug. */
    public static final String DEPENDENCIES = "deps:";

    /**
     * Marks a payload as coming from a confirmation button, wrapping the screen to return to.
     *
     * <p>Confirming has to be a different press from asking. Without this the confirmation was
     * "run the same command twice", which meant pressing a remove or version button twice in a row
     * carried the action out without the dialog ever being read.</p>
     */
    public static final String CONFIRM = "confirm:";

    /** Marks an install as covering everything it requires, wrapping the screen to return to. */
    public static final String WITH_DEPENDENCIES = "all:";

    /** Marks an install as deliberately leaving what it requires unmet. */
    public static final String ALONE = "alone:";

    private final Map<String, String> pending = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        forget(event.getPlayer(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsoleCommand(ServerCommandEvent event) {
        forget(event.getSender(), "/" + event.getCommand());
    }

    /**
     * Forgets a screen nobody claimed.
     *
     * <p>A handler can return before taking its screen — an unknown plugin name is enough — and one
     * left behind would then be read by whatever ran next, redrawing a screen that command was never
     * launched from. A command typed by hand therefore clears whatever is held.</p>
     *
     * <p>The wrapper does not match here: it is {@code /catalog-do}, not {@code /catalog}, so a
     * press never clears the screen it is in the middle of delivering.</p>
     */
    private void forget(CommandSender sender, String message) {

        if (isCatalog(message)) {
            pending.remove(sender.getName());
        }
    }

    /**
     * Remembers the screen and runs what the button meant.
     *
     * @param arguments everything after the wrapper: a screen, a space, then the real command
     */
    public void press(CommandSender sender, String arguments) {

        String rest = arguments.trim();
        int space = rest.indexOf(' ');

        if (space < 0) {
            return;
        }

        String screen = rest.substring(0, space);
        String command = rest.substring(space + 1).trim();

        // Nothing but Catalog's own commands, so typing the wrapper by hand is never a way to run
        // something else. It would run as the sender either way, but there is no reason to allow it.
        if (!isCatalog(command)) {
            return;
        }

        if (NOWHERE.equals(screen)) {
            pending.remove(sender.getName());
        } else {
            pending.put(sender.getName(), screen);
        }

        // Dispatching does not fire these events again, so the screen just stored survives into the
        // handler rather than being cleared by the command it was stored for.
        Bukkit.dispatchCommand(sender, command.substring(1));
    }

    private static boolean isCatalog(String message) {

        String lower = message.toLowerCase(Locale.ROOT);
        return lower.startsWith("/catalog ") || lower.startsWith("/ctlg ");
    }

    /**
     * Wraps a command so that pressing it also says which screen it was pressed on.
     *
     * @param command the command to run, leading slash and all
     * @param screen  the screen it is being offered from, or null when it is not from one
     * @return what the button should run
     */
    public static String press(String command, String screen) {
        return CLICK + (screen == null || screen.isEmpty() ? NOWHERE : screen) + " " + command;
    }

    /**
     * Reads and forgets the screen for a sender.
     *
     * @param sender who pressed the button
     * @return the screen, or null if the command was typed
     */
    public String take(CommandSender sender) {
        return pending.remove(sender.getName());
    }

    /**
     * The command a button really runs, for showing in its hover text.
     *
     * <p>The wrapper is not something anyone would type, and showing it would only invite someone
     * to try.</p>
     *
     * @param command the button's command
     * @return the command it stands for
     */
    public static String strip(String command) {

        if (!command.startsWith(CLICK)) {
            return command;
        }

        String rest = command.substring(CLICK.length()).trim();
        int space = rest.indexOf(' ');

        return space < 0 ? rest : rest.substring(space + 1).trim();
    }

}
