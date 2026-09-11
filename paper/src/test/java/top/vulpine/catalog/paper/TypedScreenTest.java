package top.vulpine.catalog.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.vulpine.catalog.paper.command.Messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a console reads in place of a button.
 */
class TypedScreenTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(Messages.typed(component));
    }

    private static Component widget(String label, String command) {
        return Component.text("[", NamedTextColor.GRAY)
                .append(Component.text(label, NamedTextColor.WHITE))
                .append(Component.text("]", NamedTextColor.GRAY))
                .clickEvent(ClickEvent.runCommand(command))
                .insertion(command);
    }

    @Test
    @DisplayName("a button becomes the command it runs")
    void buttonBecomesCommand() {
        assertEquals("[/catalog list]", plain(widget("Back", "/catalog list")));
    }

    @Test
    @DisplayName("text with no command is left alone")
    void plainTextSurvives() {
        assertEquals("3 required missing",
                plain(Component.text("3 required missing", NamedTextColor.GRAY)));
    }

    @Test
    @DisplayName("a row keeps its text and swaps only its buttons")
    void rowKeepsSurroundingText() {

        Component row = Component.text("  ")
                .append(Component.text("LuckPerms  "))
                .append(widget("Remove", "/catalog uninstall luckperms"));

        assertEquals("  LuckPerms  [/catalog uninstall luckperms]", plain(row));
    }

    @Test
    @DisplayName("every button in a row is swapped")
    void everyButtonInARow() {

        Component row = Component.text("  ")
                .append(widget("Confirm", "/catalog trash delete all"))
                .append(Component.text(" "))
                .append(widget("Cancel", "/catalog trash"));

        assertEquals("  [/catalog trash delete all] [/catalog trash]", plain(row));
    }

}
