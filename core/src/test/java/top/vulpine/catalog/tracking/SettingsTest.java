package top.vulpine.catalog.tracking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.vulpine.catalog.history.History;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    @TempDir
    Path directory;

    private Settings settings;
    private TrackingStore store;
    private TrackedPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {

        store = new TrackingStore(directory.resolve("tracked.json"));
        store.load();

        settings = new Settings(store, () -> TrackingDefaults.builder()
                .channel(ReleaseChannel.RELEASE)
                .autoUpdate(false)
                .soakMinutes(120)
                .build(), new History(directory.resolve("history.json")));

        plugin = new TrackedPlugin();
        plugin.projectId("P7dR8mSH");
        plugin.name("LuckPerms");
        plugin.versionId("aBc12XyZ");
        store.put(plugin);
    }

    @Test
    void channelIsWrittenThrough() throws Exception {
        settings.channel(plugin, ReleaseChannel.BETA, "vulpine");
        assertEquals(ReleaseChannel.BETA, plugin.channel());
    }

    @Test
    void negativeSoakBecomesNone() throws Exception {
        settings.soak(plugin, -5, "vulpine");
        assertEquals(0, plugin.soakMinutes());
    }

    @Test
    void inheritIsKeptRatherThanClamped() throws Exception {
        settings.soak(plugin, TrackedPlugin.INHERIT_SOAK, "vulpine");
        assertEquals(TrackedPlugin.INHERIT_SOAK, plugin.soakMinutes());
    }

    @Test
    void holdingPinsTheInstalledBuild() throws Exception {
        settings.held(plugin, true, "vulpine");
        assertTrue(plugin.isPinned());
        assertEquals("aBc12XyZ", plugin.pinnedVersionId());
    }

    @Test
    void releasingClearsThePin() throws Exception {
        settings.held(plugin, true, "vulpine");
        settings.held(plugin, false, "vulpine");
        assertFalse(plugin.isPinned());
        assertNull(plugin.pinnedVersionId());
    }

    @Test
    void defaultSoakComesFromTheConfig() {
        assertEquals(120, settings.defaultSoakMinutes());
    }

    @Test
    void everyChangeIsPersisted() throws Exception {

        settings.channel(plugin, ReleaseChannel.ALPHA, "vulpine");

        TrackingStore reopened = new TrackingStore(directory.resolve("tracked.json"));
        reopened.load();

        assertEquals(ReleaseChannel.ALPHA, reopened.byProjectId("P7dR8mSH").channel());
    }

}
