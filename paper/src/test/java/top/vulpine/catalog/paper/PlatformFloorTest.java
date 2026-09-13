package top.vulpine.catalog.paper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check that refuses to start on a server older than Catalog supports.
 */
@DisplayName("platform floor")
class PlatformFloorTest {

    private static final String MINIMUM = "1.18.2";

    @Test
    @DisplayName("the versions below the floor are refused")
    void belowIsRefused() {

        assertTrue(CatalogPaper.isOlderThan("1.18.1", MINIMUM));
        assertTrue(CatalogPaper.isOlderThan("1.17.1", MINIMUM));
        assertTrue(CatalogPaper.isOlderThan("1.8.8", MINIMUM));
        assertTrue(CatalogPaper.isOlderThan("1.16", MINIMUM));
    }

    @Test
    @DisplayName("the floor itself is accepted")
    void floorIsAccepted() {
        assertFalse(CatalogPaper.isOlderThan("1.18.2", MINIMUM));
    }

    @Test
    @DisplayName("later versions of the old numbering are accepted")
    void laterOldNumberingIsAccepted() {

        assertFalse(CatalogPaper.isOlderThan("1.19", MINIMUM));
        assertFalse(CatalogPaper.isOlderThan("1.20.4", MINIMUM));
        assertFalse(CatalogPaper.isOlderThan("1.21.11", MINIMUM));
    }

    /**
     * Paper moved from 1.21 to 26. Ordering these as text puts 26 before 1.18, which would refuse
     * to start on every current server.
     */
    @Test
    @DisplayName("the new numbering is accepted")
    void newNumberingIsAccepted() {

        assertFalse(CatalogPaper.isOlderThan("26.1.2", MINIMUM));
        assertFalse(CatalogPaper.isOlderThan("26.2", MINIMUM));
    }

    @Test
    @DisplayName("a version that cannot be read starts anyway")
    void unreadableStarts() {

        assertFalse(CatalogPaper.isOlderThan("not-a-version", MINIMUM));
        assertFalse(CatalogPaper.isOlderThan("", MINIMUM));
    }

}
