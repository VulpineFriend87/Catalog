package top.vulpine.catalog.paper.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rule that a command must read its screen before it leaves the dispatch thread.
 *
 * <p>{@link ClickContext#press} clears the screen in a {@code finally} the moment Lamp's dispatch
 * returns. A command body that hops to another thread first therefore finds nothing, and the button
 * silently stops redrawing the screen it was pressed on. Nothing in the types says so, so it is
 * said here.</p>
 */
@DisplayName("click context")
class ClickContextRuleTest {

    private static final Path SOURCE =
            Path.of("src/main/java/top/vulpine/catalog/paper/command/MainCommand.java");

    private static final Pattern METHOD =
            Pattern.compile("^ {4}(?:public|private)\\s+[\\w<>,\\[\\]\\s]+\\s(\\w+)\\(");

    @Test
    @DisplayName("every command reads its screen before going async")
    void takeComesBeforeAsync() throws IOException {

        List<String> lines = Files.readAllLines(SOURCE, StandardCharsets.UTF_8);
        List<String> offenders = new ArrayList<>();

        String method = "?";
        boolean async = false;

        for (int i = 0; i < lines.size(); i++) {

            String line = lines.get(i);
            Matcher matcher = METHOD.matcher(line);

            if (matcher.find()) {
                method = matcher.group(1);
                async = false;
            }

            if (line.contains("runAsync")) {
                async = true;
            }

            if (line.contains("context.take(sender)") && async) {
                offenders.add(method + " at line " + (i + 1));
            }
        }

        if (!offenders.isEmpty()) {
            throw new AssertionError("These read the screen after going async, so the button they "
                    + "were pressed from will not redraw: " + String.join(", ", offenders)
                    + ". Take it into a local before runAsync, as every other command does.");
        }
    }

}
