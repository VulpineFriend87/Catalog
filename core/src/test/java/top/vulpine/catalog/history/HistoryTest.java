package top.vulpine.catalog.history;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.vulpine.catalog.tracking.model.TrackedPlugin;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record of what Catalog did.
 */
class HistoryTest {

    @TempDir
    Path directory;

    private History history;

    @BeforeEach
    void setUp() {
        history = new History(directory.resolve("history.json"));
        history.load();
    }

    private static TrackedPlugin plugin(String projectId, String name) {
        TrackedPlugin tracked = new TrackedPlugin();
        tracked.projectId(projectId);
        tracked.name(name);
        tracked.versionNumber("1.0");
        return tracked;
    }

    @Test
    @DisplayName("the newest entry comes first")
    void newestFirst() {

        history.add(HistoryEntry.trashed(plugin("a", "First"), "vulpine"));
        history.add(HistoryEntry.trashed(plugin("b", "Second"), "vulpine"));

        assertEquals("Second", history.all().get(0).name());
    }

    @Test
    @DisplayName("entries survive a reload")
    void survivesReload() {

        history.add(HistoryEntry.installed(plugin("a", "LuckPerms"), "vulpine", null));

        History reopened = new History(directory.resolve("history.json"));
        reopened.load();

        assertEquals(1, reopened.all().size());
        assertEquals("LuckPerms", reopened.all().get(0).name());
        assertEquals(Event.INSTALLED, reopened.all().get(0).event());
    }

    @Test
    @DisplayName("the oldest entries drop once the cap is reached")
    void cappedAtTheLimit() {

        for (int i = 0; i < History.LIMIT + 20; i++) {
            history.add(HistoryEntry.trashed(plugin("p" + i, "Plugin " + i), "vulpine"));
        }

        assertEquals(History.LIMIT, history.all().size());
        assertEquals("Plugin " + (History.LIMIT + 19), history.all().get(0).name());
    }

    @Test
    @DisplayName("filtering by plugin keeps only that plugin")
    void filterByPlugin() {

        history.add(HistoryEntry.trashed(plugin("a", "LuckPerms"), "vulpine"));
        history.add(HistoryEntry.trashed(plugin("b", "WorldEdit"), "vulpine"));

        List<HistoryEntry> only = history.filter("a", null);

        assertEquals(1, only.size());
        assertEquals("LuckPerms", only.get(0).name());
    }

    @Test
    @DisplayName("filtering by author is case insensitive")
    void filterByAuthor() {

        history.add(HistoryEntry.trashed(plugin("a", "LuckPerms"), "VulpineFriend87"));
        history.add(HistoryEntry.trashed(plugin("b", "WorldEdit"), "Console"));

        assertEquals(1, history.filter(null, "vulpinefriend87").size());
        assertEquals("LuckPerms", history.filter(null, "VULPINEFRIEND87").get(0).name());
    }

    @Test
    @DisplayName("filtering by Catalog finds what it did on its own")
    void filterByCatalog() {

        history.add(HistoryEntry.trashed(plugin("a", "LuckPerms"), "vulpine"));
        history.add(HistoryEntry.found(plugin("b", "WorldEdit"), Event.REPLACED_BY_HAND));

        List<HistoryEntry> alone = history.filter(null, "catalog");

        assertEquals(1, alone.size());
        assertEquals("WorldEdit", alone.get(0).name());
        assertFalse(alone.get(0).byPerson());
    }

    @Test
    @DisplayName("both filters narrow together")
    void bothFilters() {

        history.add(HistoryEntry.trashed(plugin("a", "LuckPerms"), "vulpine"));
        history.add(HistoryEntry.trashed(plugin("a", "LuckPerms"), "Console"));
        history.add(HistoryEntry.trashed(plugin("b", "WorldEdit"), "vulpine"));

        assertEquals(1, history.filter("a", "vulpine").size());
    }

    @Test
    @DisplayName("an action without a person is recorded as Catalog")
    void catalogIsAbsence() {

        history.add(HistoryEntry.installed(plugin("a", "LuckPerms"), null, null));

        assertFalse(history.all().get(0).byPerson());
    }

    @Test
    @DisplayName("a dependency install names the plugin that pulled it in")
    void dependencyNamesItsRoot() {

        history.add(HistoryEntry.installed(plugin("via", "ViaVersion"), "vulpine", "ViaRewind"));

        HistoryEntry entry = history.all().get(0);

        assertEquals(Event.INSTALLED_AS_DEPENDENCY, entry.event());
        assertEquals("ViaRewind", entry.value());
    }

    @Test
    @DisplayName("an install asked for outright is not a dependency")
    void explicitInstallHasNoRoot() {

        history.add(HistoryEntry.installed(plugin("a", "LuckPerms"), "vulpine", null));

        assertEquals(Event.INSTALLED, history.all().get(0).event());
    }

    @Test
    @DisplayName("a settings event is marked quieter than an action")
    void settingsAreQuieter() {

        history.add(HistoryEntry.setting(plugin("a", "Vault"), Event.HELD, null, "vulpine"));

        assertTrue(history.all().get(0).event().isSetting());
        assertFalse(Event.INSTALLED.isSetting());
    }

    @Test
    @DisplayName("a plugin no longer installed can still be looked up")
    void removedPluginIsStillFilterable() {

        TrackedPlugin gone = plugin("ess", "EssentialsX");
        gone.slug("essentialsx");

        history.add(HistoryEntry.installed(gone, "vulpine", null));
        history.add(HistoryEntry.trashed(gone, "vulpine"));

        assertEquals("ess", history.projectIdFor("EssentialsX"));
        assertEquals("ess", history.projectIdFor("essentialsx"));
        assertEquals("ess", history.projectIdFor("ess"));
        assertEquals(2, history.filter("ess", null).size());
    }

    @Test
    @DisplayName("a plugin the log never mentions resolves to nothing")
    void unknownPluginIsNull() {
        history.add(HistoryEntry.trashed(plugin("a", "LuckPerms"), "vulpine"));
        assertNull(history.projectIdFor("WorldEdit"));
    }

    @Test
    @DisplayName("completion lists each plugin once")
    void completionIsDistinct() {

        TrackedPlugin luck = plugin("a", "LuckPerms");
        luck.slug("luckperms");

        history.add(HistoryEntry.installed(luck, "vulpine", null));
        history.add(HistoryEntry.trashed(luck, "vulpine"));
        history.add(HistoryEntry.restored(luck, "vulpine"));

        assertEquals(List.of("luckperms"), history.plugins());
    }

    @Test
    @DisplayName("a missing file simply starts empty")
    void missingFileIsEmpty() {

        History fresh = new History(directory.resolve("nothing-here.json"));
        fresh.load();

        assertTrue(fresh.all().isEmpty());
    }

}
