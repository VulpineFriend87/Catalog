package top.vulpine.catalog.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExitTasks")
class ExitTasksTest {

    /** No process has this id, so the second JVM's wait returns at once. */
    private static final String GONE = String.valueOf(Long.MAX_VALUE);

    @TempDir
    Path root;

    private ExitTasks tasks() throws IOException {
        return new ExitTasks(Files.createDirectories(root.resolve("data")), null);
    }

    private Path file(String name, String contents) throws IOException {
        Files.createDirectories(root.resolve("plugins"));
        Files.createDirectories(root.resolve("update"));
        return Files.writeString(root.resolve(name), contents);
    }

    @Test
    @DisplayName("replaces the installed jar and gives it the published name")
    void replaces() throws IOException {

        Path installed = file("plugins/LuckPerms-5.5.17.jar", "old");
        Path staged = file("update/LuckPerms-5.5.71.jar", "new");
        Path published = root.resolve("plugins/LuckPerms-5.5.71.jar");

        ExitTasks tasks = tasks();
        tasks.replace(staged, installed, published);
        tasks.run();

        assertAll(
                () -> assertFalse(Files.exists(installed)),
                () -> assertFalse(Files.exists(staged)),
                () -> assertEquals("new", Files.readString(published))
        );
    }

    @Test
    @DisplayName("keeps the old name when the published one is taken")
    void keepsTheOldName() throws IOException {

        Path installed = file("plugins/LuckPerms.jar", "old");
        Path staged = file("update/LuckPerms-5.5.71.jar", "new");
        Path published = file("plugins/LuckPerms-5.5.71.jar", "someone else's");

        ExitTasks tasks = tasks();
        tasks.replace(staged, installed, published);
        tasks.run();

        assertAll(
                () -> assertEquals("new", Files.readString(installed)),
                () -> assertEquals("someone else's", Files.readString(published))
        );
    }

    @Test
    @DisplayName("does nothing for a build that is no longer staged")
    void missingBuild() throws IOException {

        Path installed = file("plugins/LuckPerms.jar", "old");

        ExitTasks tasks = tasks();
        tasks.replace(root.resolve("update/gone.jar"), installed, root.resolve("plugins/gone.jar"));
        tasks.run();

        assertEquals("old", Files.readString(installed));
    }

    @Test
    @DisplayName("deletes a jar")
    void deletes() throws IOException {

        Path jar = file("plugins/ViaBackwards.jar", "x");

        ExitTasks tasks = tasks();
        tasks.delete(jar);
        tasks.run();

        assertFalse(Files.exists(jar));
    }

    @Test
    @DisplayName("a withdrawn deletion leaves the jar alone")
    void withdraws() throws IOException {

        Path jar = file("plugins/ViaBackwards.jar", "x");

        ExitTasks tasks = tasks();
        tasks.delete(jar);

        assertTrue(tasks.withdraw(jar));
        assertFalse(tasks.withdraw(jar), "nothing is left to withdraw");

        tasks.run();

        assertTrue(Files.exists(jar));
    }

    @Test
    @DisplayName("the later task for a jar replaces the earlier one")
    void laterWins() throws IOException {

        Path installed = file("plugins/LuckPerms.jar", "old");
        Path staged = file("update/LuckPerms-5.5.71.jar", "new");

        ExitTasks tasks = tasks();
        tasks.delete(installed);
        tasks.replace(staged, installed, installed);
        tasks.run();

        assertEquals("new", Files.readString(installed));
    }

    @Test
    @DisplayName("the second JVM reads its tasks from the command line and leaves no log")
    void secondJvm() throws Exception {

        Path installed = file("plugins/LuckPerms-5.5.17.jar", "old");
        Path staged = file("update/LuckPerms-5.5.71.jar", "new");
        Path published = root.resolve("plugins/LuckPerms-5.5.71.jar");
        Path removed = file("plugins/ViaBackwards.jar", "x");
        Path log = root.resolve("last-exit.log");

        ExitTasks.main(new String[]{GONE, log.toString(),
                "replace", staged.toString(), installed.toString(), published.toString(),
                "delete", removed.toString()});

        assertAll(
                () -> assertEquals("new", Files.readString(published)),
                () -> assertFalse(Files.exists(installed)),
                () -> assertFalse(Files.exists(removed)),
                () -> assertFalse(Files.exists(log))
        );
    }

}
