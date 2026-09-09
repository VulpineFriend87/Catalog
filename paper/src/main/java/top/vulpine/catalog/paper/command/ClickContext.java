package top.vulpine.catalog.paper.command;

import org.bukkit.command.CommandSender;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

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
 * whether an unrecognised command was really meant.</p>
 *
 * <p>The command is handed straight to the tree rather than out through Bukkit and back. That is
 * what gives the screen a lifetime: Lamp runs the command inline, so it has been read by the time
 * the call returns and can be cleared here. Bukkit's own dispatch returns before the command has
 * run, which would leave a screen nobody claimed sitting there for whatever executed next to pick
 * up. Both were measured rather than assumed.</p>
 */
public final class ClickContext {

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

    /**
     * Set once the tree exists, because the commands it holds are built with this in hand.
     */
    private Lamp<BukkitCommandActor> lamp;

    public void dispatcher(Lamp<BukkitCommandActor> lamp) {
        this.lamp = lamp;
    }

    /**
     * Remembers the screen and runs what the button meant.
     *
     * @param arguments everything after the wrapper: a screen, a space, then the real command
     */
    public void press(BukkitCommandActor actor, String arguments) {

        CommandSender sender = actor.sender();
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

        String name = sender.getName();

        if (NOWHERE.equals(screen)) {
            pending.remove(name);
        } else {
            pending.put(name, screen);
        }

        // Straight to the tree rather than out through Bukkit and back. Bukkit's dispatch returns
        // before the command it was given has run, which leaves the screen with no lifetime anyone
        // can reason about; Lamp runs it inline, so it is read before this method returns and can
        // be cleared here.
        try {
            lamp.dispatch(actor, command.substring(1));
        } finally {
            pending.remove(name);
        }
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
