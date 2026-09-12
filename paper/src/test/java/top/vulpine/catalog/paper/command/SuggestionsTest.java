package top.vulpine.catalog.paper.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Narrowing completions to what has been typed.
 */
class SuggestionsTest {

    private static final List<String> PLUGINS =
            List.of("LuckPerms", "WorldEdit", "WorldGuard", "\"Axiom Paper Plugin\"");

    @Test
    @DisplayName("nothing typed offers everything")
    void emptyOffersAll() {
        assertEquals(PLUGINS, Suggestions.matching("", PLUGINS));
    }

    @Test
    @DisplayName("a prefix narrows to what starts with it")
    void prefixNarrows() {
        assertEquals(List.of("WorldEdit", "WorldGuard"), Suggestions.matching("World", PLUGINS));
    }

    @Test
    @DisplayName("matching ignores case")
    void caseInsensitive() {
        assertEquals(List.of("LuckPerms"), Suggestions.matching("luckp", PLUGINS));
    }

    @Test
    @DisplayName("a name offered in quotes matches without typing the quote")
    void quotedNameMatchesBare() {
        assertEquals(List.of("\"Axiom Paper Plugin\""), Suggestions.matching("Axiom", PLUGINS));
    }

    @Test
    @DisplayName("a typed quote matches the quoted name")
    void typedQuoteMatches() {
        assertEquals(List.of("\"Axiom Paper Plugin\""), Suggestions.matching("\"Axi", PLUGINS));
    }

    @Test
    @DisplayName("a prefix nothing starts with offers nothing")
    void noMatchOffersNothing() {
        assertEquals(List.of(), Suggestions.matching("zzz", PLUGINS));
    }

    @Test
    @DisplayName("the full name still matches itself")
    void exactStillMatches() {
        assertEquals(List.of("LuckPerms"), Suggestions.matching("LuckPerms", PLUGINS));
    }

}
