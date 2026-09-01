package top.vulpine.catalog.tracking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("IgnoreList")
class IgnoreListTest {

    @TempDir
    Path data;

    private Path file() {
        return data.resolve("ignored.json");
    }

    @Test
    @DisplayName("an untracked plugin stays untracked across a restart")
    void survivesReload() {

        IgnoreList list = new IgnoreList(file());
        list.load();
        list.add("AABBCCDD", "deadbeef");
        list.save();

        IgnoreList reloaded = new IgnoreList(file());
        reloaded.load();

        assertTrue(reloaded.contains("AABBCCDD", null), "otherwise the scan would adopt it again");
        assertTrue(reloaded.contains(null, "deadbeef"));
    }

    @Test
    @DisplayName("matches on either the project id or the hash")
    void matchesEitherIdentity() {

        IgnoreList list = new IgnoreList(file());
        list.load();
        list.add("AABBCCDD", "deadbeef");

        assertAllOf(
                list.contains("AABBCCDD", "a-different-hash"),
                list.contains("a-different-project", "deadbeef"),
                !list.contains("unknown", "unknown")
        );
    }

    @Test
    @DisplayName("a jar Modrinth cannot identify is still ignorable, by hash alone")
    void ignoresUnidentifiedJars() {

        IgnoreList list = new IgnoreList(file());
        list.load();
        list.add(null, "deadbeef");

        assertTrue(list.contains(null, "deadbeef"));
        assertEquals(1, list.size());
    }

    @Test
    @DisplayName("removing puts a plugin back in reach of the scan")
    void removeAllowsAdoptionAgain() {

        IgnoreList list = new IgnoreList(file());
        list.load();
        list.add("AABBCCDD", "deadbeef");

        assertTrue(list.remove("AABBCCDD", "deadbeef"));
        assertFalse(list.contains("AABBCCDD", "deadbeef"));
        assertFalse(list.remove("AABBCCDD", "deadbeef"), "removing twice reports nothing happened");
    }

    @Test
    @DisplayName("a corrupt file fails soft, because the worst case is only an unwanted offer")
    void failsSoftOnCorruption() throws IOException {

        Files.writeString(file(), "not json");

        IgnoreList list = new IgnoreList(file());
        list.load();

        assertEquals(0, list.size(), "empty rather than refusing to start");
    }

    private static void assertAllOf(boolean... conditions) {

        for (int i = 0; i < conditions.length; i++) {
            assertTrue(conditions[i], "condition " + i);
        }
    }

}
