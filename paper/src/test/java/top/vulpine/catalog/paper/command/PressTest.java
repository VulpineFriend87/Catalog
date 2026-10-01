package top.vulpine.catalog.paper.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("press")
class PressTest {

    @Test
    @DisplayName("confirming keeps the answer given before it")
    void confirmingKeepsTheAnswer() {

        Press asked = Press.on("info:luckperms").answering(ClickContext.ALONE);
        Press confirmed = Press.of(asked.confirming().data());

        assertTrue(confirmed.confirmed());
        assertEquals(ClickContext.ALONE, confirmed.answer());
        assertEquals("info:luckperms", confirmed.screen());
    }

    @Test
    @DisplayName("every field survives being written and read back")
    void roundTrips() {

        for (Press press : new Press[]{
                Press.on(null),
                Press.on("list:updates,held"),
                Press.on("do:install:luckperms@abc123").answering(ClickContext.ALONE),
                Press.on(null).answering(ClickContext.ALONE).confirming()}) {
            assertEquals(press, Press.of(press.data()));
        }
    }

    @Test
    @DisplayName("a typed command carries nothing")
    void typedIsNull() {
        assertNull(Press.of(null));
    }

    @Test
    @DisplayName("a plain screen is not a confirmation")
    void plainScreen() {

        Press press = Press.of("trash");

        assertFalse(press.confirmed());
        assertNull(press.answer());
        assertEquals("trash", press.screen());
    }

}
