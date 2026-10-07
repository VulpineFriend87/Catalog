package top.vulpine.catalog.platform;

import top.vulpine.catalog.CatalogAction;
import top.vulpine.commons.log.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Jars to replace or delete as the JVM exits.
 *
 * <p>{@link #run()} does them from a shutdown hook. Jars that are still locked, which on Windows is
 * every jar Velocity loaded, go to a second JVM started on {@link #main}, which waits for this one
 * to exit and tries again. What fails there too is written to a log that
 * {@link #report()} reads on the next start.</p>
 */
public final class ExitTasks {

    private static final String REPLACE = "replace";
    private static final String DELETE = "delete";

    private static final int ATTEMPTS = 20;
    private static final long PAUSE_MILLIS = 500;

    private final Path dataFolder;
    private final Path ownJar;

    /** Keyed by the jar in the plugins folder, so a later task for the same jar wins. */
    private final Map<Path, Task> tasks = new ConcurrentHashMap<>();

    /**
     * @param dataFolder where the log and the copy of Catalog's jar go
     * @param ownJar     the jar Catalog runs from, which the second JVM is started on a copy of
     */
    public ExitTasks(Path dataFolder, Path ownJar) {
        this.dataFolder = dataFolder;
        this.ownJar = ownJar;
    }

    /**
     * Replaces an installed jar with a downloaded build, then gives it the published name.
     *
     * @param staged    the downloaded build
     * @param installed the jar it replaces
     * @param published the name it ends up with
     */
    public void replace(Path staged, Path installed, Path published) {
        tasks.put(installed, new Task(REPLACE, staged, installed, published));
    }

    /**
     * @param jar the jar to delete
     */
    public void delete(Path jar) {
        tasks.put(jar, new Task(DELETE, null, jar, null));
    }

    /**
     * Calls off whatever was waiting for a jar.
     *
     * @param jar the jar in the plugins folder
     * @return true if something was waiting
     */
    public boolean withdraw(Path jar) {
        return tasks.remove(jar) != null;
    }

    /**
     * Does every task, for a shutdown hook. Never throws.
     */
    public void run() {

        List<Task> held = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (Task task : tasks.values()) {
            try {
                task.apply(failures);
            } catch (IOException e) {
                held.add(task);
            }
        }

        if (!held.isEmpty() && !handOff(held)) {
            for (Task task : held) {
                failures.add(task.describe() + " at shutdown: the file was locked");
            }
        }

        write(failures);
    }

    /**
     * Logs what the last exit could not do and clears up after it. Call once on startup.
     */
    public void report() {

        Path log = log();

        try {
            Files.deleteIfExists(copy());

            if (Files.exists(log)) {
                for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                    Logger.warn(CatalogAction.UPDATE, line);
                }
                Files.delete(log);
            }

        } catch (IOException e) {
            Logger.warn(CatalogAction.UPDATE, "Could not read " + log.getFileName() + ": "
                    + e.getMessage());
        }
    }

    /**
     * Starts the second JVM on a copy of Catalog's jar, so that Catalog can replace its own.
     *
     * @return false if it could not be started
     */
    private boolean handOff(List<Task> held) {

        if (ownJar == null || !Files.isRegularFile(ownJar)) {
            return false;
        }

        try {
            Files.copy(ownJar, copy(), StandardCopyOption.REPLACE_EXISTING);

            String java = ProcessHandle.current().info().command()
                    .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());

            List<String> command = new ArrayList<>(List.of(java, "-cp", copy().toString(),
                    ExitTasks.class.getName(), String.valueOf(ProcessHandle.current().pid()),
                    log().toString()));

            for (Task task : held) {
                command.addAll(task.arguments());
            }

            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            return true;

        } catch (IOException e) {
            return false;
        }
    }

    private void write(List<String> failures) {
        write(log(), failures);
    }

    private Path log() {
        return dataFolder.resolve("last-exit.log");
    }

    private Path copy() {
        return dataFolder.resolve("exit-tasks.jar");
    }

    /**
     * The second JVM: waits for the server's process to exit, then does the tasks it was given.
     *
     * @param args the server's process id, the log, then each task as its kind and paths
     */
    public static void main(String[] args) throws Exception {

        Optional<ProcessHandle> server = ProcessHandle.of(Long.parseLong(args[0]));

        if (server.isPresent()) {
            server.get().onExit().get(2, TimeUnit.MINUTES);
        }

        List<String> failures = new ArrayList<>();

        for (Task task : Task.parse(args, 2)) {
            try {
                task.applyRetrying(failures);
            } catch (IOException e) {
                failures.add(task.describe() + " after the server exited: " + e);
            }
        }

        write(Path.of(args[1]), failures);
    }

    private static void write(Path log, List<String> failures) {

        try {
            if (failures.isEmpty()) {
                Files.deleteIfExists(log);
            } else {
                Files.write(log, failures, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // There is no longer anywhere to report this to.
        }
    }

    /**
     * One change to one jar.
     *
     * @param staged    the downloaded build, for a replacement
     * @param jar       the jar in the plugins folder
     * @param published the name a replacement ends up with
     */
    private record Task(String kind, Path staged, Path jar, Path published) {

        /**
         * @throws IOException if the jar is locked
         */
        void apply(List<String> failures) throws IOException {

            if (kind.equals(DELETE)) {
                Files.deleteIfExists(jar);
                return;
            }

            if (!Files.exists(staged)) {
                return;
            }

            // Over the old jar first, in one move: a failure then leaves the old jar alone rather
            // than two jars of the same plugin.
            move(staged, jar);

            if (!jar.equals(published) && !Files.exists(published)) {
                try {
                    move(jar, published);
                } catch (IOException e) {
                    failures.add("Applied " + published.getFileName() + " under the old name "
                            + jar.getFileName() + ": " + e);
                }
            }
        }

        void applyRetrying(List<String> failures) throws IOException {

            IOException last = null;

            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                try {
                    apply(failures);
                    return;
                } catch (IOException e) {
                    last = e;
                    pause();
                }
            }

            throw last;
        }

        String describe() {
            return kind.equals(DELETE)
                    ? "Could not delete " + jar.getFileName()
                    : "Could not replace " + jar.getFileName() + " with " + staged.getFileName();
        }

        List<String> arguments() {
            return kind.equals(DELETE)
                    ? List.of(DELETE, jar.toString())
                    : List.of(REPLACE, staged.toString(), jar.toString(), published.toString());
        }

        static List<Task> parse(String[] args, int from) {

            List<Task> parsed = new ArrayList<>();
            int i = from;

            while (i < args.length) {
                if (args[i].equals(DELETE)) {
                    parsed.add(new Task(DELETE, null, Path.of(args[i + 1]), null));
                    i += 2;
                } else {
                    parsed.add(new Task(REPLACE, Path.of(args[i + 1]), Path.of(args[i + 2]),
                            Path.of(args[i + 3])));
                    i += 4;
                }
            }

            return parsed;
        }
    }

    private static void move(Path from, Path to) throws IOException {

        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void pause() {

        try {
            Thread.sleep(PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
