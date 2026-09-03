package top.vulpine.catalog.tracking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.vulpine.catalog.jar.model.InstalledJar;
import top.vulpine.catalog.jar.model.PluginDescriptor;
import top.vulpine.catalog.jar.model.ScanResult;
import top.vulpine.catalog.json.Json;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.tracking.model.ReconcileReport;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Reconciler")
class ReconcilerTest {

    @TempDir
    Path data;

    private TrackingStore store;
    private IgnoreList ignoreList;
    private Reconciler reconciler;

    private final List<InstalledJar> jars = new ArrayList<>();
    private final Map<String, ModrinthVersion> identified = new HashMap<>();

    @BeforeEach
    void setUp() {
        store = new TrackingStore(data.resolve("tracked.json"));
        ignoreList = new IgnoreList(data.resolve("ignored.json"));
        reconciler = new Reconciler(store, ignoreList, TrackingDefaults.standard());
    }

    // --- fixtures ---------------------------------------------------------------------------

    private static ModrinthVersion version(String versionId, String projectId) {
        return Json.gson().fromJson("""
                {
                  "id": "%s",
                  "project_id": "%s",
                  "version_number": "1.0",
                  "version_type": "release",
                  "date_published": "2026-08-08T10:00:00Z",
                  "loaders": ["paper"],
                  "game_versions": ["1.21.4"],
                  "files": [],
                  "dependencies": []
                }
                """.formatted(versionId, projectId), ModrinthVersion.class);
    }

    /** Adds a jar to the pretend plugins folder, and says what Modrinth makes of it. */
    private InstalledJar onDisk(String fileName, String hash, String pluginName, ModrinthVersion known) {

        PluginDescriptor descriptor = PluginDescriptor.builder()
                .pluginName(pluginName)
                .kind(PluginDescriptor.Kind.BUKKIT)
                .build();

        InstalledJar jar = new InstalledJar(Path.of(fileName), 1024L, 1L, hash, descriptor);
        jars.add(jar);

        if (known != null) {
            identified.put(hash, known);
        }

        return jar;
    }

    private ReconcileReport run() {
        return reconciler.reconcile(new ScanResult(jars, List.of()), identified);
    }

    // --- the four branches ------------------------------------------------------------------

    @Test
    @DisplayName("a recognised jar is adopted with the configured defaults")
    void adoptsRecognisedJars() {

        onDisk("LuckPerms.jar", "hash-a", "LuckPerms", version("v1", "PROJ-A"));

        ReconcileReport report = run();

        assertEquals(1, report.adopted().size());

        TrackedPlugin tracked = store.byProjectId("PROJ-A");

        assertNotNull(tracked);
        assertEquals("LuckPerms.jar", tracked.fileName());
        assertFalse(tracked.autoUpdate(), "auto-update stays off until asked for");
        assertEquals(ReleaseChannel.RELEASE, tracked.channel());
        assertEquals(Reconciler.AUTO_TRACK, tracked.installedBy());
    }

    @Test
    @DisplayName("an unrecognised jar is listed and left completely alone")
    void leavesUnknownJarsAlone() {

        onDisk("SomeoneElse.jar", "hash-x", "Mystery", null);

        ReconcileReport report = run();

        assertEquals(1, report.unknown().size());
        assertEquals(0, store.size(), "nothing Catalog does not recognise gets tracked");
        assertFalse(report.hasChanges());
    }

    @Test
    @DisplayName("deleting a jar by hand stops the tracking")
    void detectsManualRemoval() {

        store.put(TrackedPlugin.of(version("v1", "PROJ-A"), "LuckPerms.jar", "hash-a",
                ReleaseChannel.RELEASE, "test"));

        ReconcileReport report = run();

        assertEquals(1, report.removed().size());
        assertNull(store.byProjectId("PROJ-A"));
    }

    @Test
    @DisplayName("replacing a jar by hand with a newer build follows it")
    void followsManualReplacement() {

        store.put(TrackedPlugin.of(version("v1", "PROJ-A"), "LuckPerms.jar", "old-hash",
                ReleaseChannel.RELEASE, "test"));

        onDisk("LuckPerms.jar", "new-hash", "LuckPerms", version("v2", "PROJ-A"));

        ReconcileReport report = run();

        assertEquals(1, report.moved().size());

        TrackedPlugin tracked = store.byProjectId("PROJ-A");

        assertEquals("v2", tracked.versionId(), "the record follows the file");
        assertEquals("new-hash", tracked.sha512());
    }

    @Test
    @DisplayName("a staged update landing is an update, not a manual replacement")
    void clearsTheFlagWhenAnUpdateLands() {

        // The jar Catalog put in the update folder is now the jar in the plugins folder. Read as a
        // manual swap it would stay marked staged forever, because nothing else ever clears it.
        TrackedPlugin staged = TrackedPlugin.of(version("v1", "PROJ-A"), "LuckPerms.jar",
                "old-hash", ReleaseChannel.RELEASE, "test");
        staged.pendingRestart(true);
        store.put(staged);

        onDisk("LuckPerms.jar", "new-hash", "LuckPerms", version("v2", "PROJ-A"));

        ReconcileReport report = run();

        assertEquals(1, report.applied().size());
        assertEquals(0, report.moved().size(), "Catalog asked for this one");

        TrackedPlugin tracked = store.byProjectId("PROJ-A");

        assertEquals("v2", tracked.versionId());
        assertFalse(tracked.pendingRestart(), "and it is no longer waiting for a restart");
    }

    @Test
    @DisplayName("a staged update that did not land is reported and stays staged")
    void reportsAnUpdateThatDidNotLand() {

        // Same bytes as before the restart: the server never took the file from the update folder.
        TrackedPlugin staged = TrackedPlugin.of(version("v1", "PROJ-A"), "LuckPerms.jar",
                "same-hash", ReleaseChannel.RELEASE, "test");
        staged.pendingRestart(true);
        store.put(staged);

        onDisk("LuckPerms.jar", "same-hash", "LuckPerms", version("v1", "PROJ-A"));

        ReconcileReport report = run();

        assertEquals(1, report.notApplied().size());
        assertTrue(report.needsAttention(), "somebody restarted for nothing and should be told");
        assertTrue(store.byProjectId("PROJ-A").pendingRestart(), "the build is still waiting");
    }

    @Test
    @DisplayName("an unstaged plugin is never reported as failing to update")
    void saysNothingAboutPluginsWithNothingStaged() {

        store.put(TrackedPlugin.of(version("v1", "PROJ-A"), "LuckPerms.jar", "same-hash",
                ReleaseChannel.RELEASE, "test"));

        onDisk("LuckPerms.jar", "same-hash", "LuckPerms", version("v1", "PROJ-A"));

        assertTrue(run().notApplied().isEmpty());
    }

    // --- the cases that are easy to get wrong ------------------------------------------------

    @Test
    @DisplayName("renaming a jar keeps the tracking, because the hash is the identity")
    void survivesRename() {

        store.put(TrackedPlugin.of(version("v1", "PROJ-A"), "LuckPerms-5.4.jar", "hash-a",
                ReleaseChannel.RELEASE, "test"));

        onDisk("(catalog) LuckPerms.jar", "hash-a", "LuckPerms", version("v1", "PROJ-A"));

        ReconcileReport report = run();

        assertEquals(1, report.renamed().size());
        assertEquals(0, report.removed().size(), "a rename must never read as an uninstall");
        assertEquals("(catalog) LuckPerms.jar", store.byProjectId("PROJ-A").fileName());
    }

    @Test
    @DisplayName("swapping a jar for a different plugin untracks the old one and adopts the new")
    void handlesSwapToAnotherProject() {

        store.put(TrackedPlugin.of(version("v1", "PROJ-A"), "plugin.jar", "old-hash",
                ReleaseChannel.RELEASE, "test"));

        onDisk("plugin.jar", "new-hash", "EssentialsX", version("v9", "PROJ-B"));

        ReconcileReport report = run();

        assertEquals(1, report.orphaned().size(), "the old project is no longer there");
        assertEquals(1, report.adopted().size(), "and the new one must not be missed");
        assertNull(store.byProjectId("PROJ-A"));
        assertNotNull(store.byProjectId("PROJ-B"));
        assertTrue(report.needsAttention());
    }

    @Test
    @DisplayName("a jar replaced with something unrecognisable is untracked, not left pointing at it")
    void orphansUnidentifiableReplacements() {

        store.put(TrackedPlugin.of(version("v1", "PROJ-A"), "plugin.jar", "old-hash",
                ReleaseChannel.RELEASE, "test"));

        onDisk("plugin.jar", "mystery-hash", "Mystery", null);

        ReconcileReport report = run();

        assertEquals(1, report.orphaned().size());
        assertEquals(1, report.unknown().size());
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("an ignored plugin is not adopted again on the next boot")
    void respectsTheIgnoreList() {

        ignoreList.add("PROJ-A", null);
        onDisk("LuckPerms.jar", "hash-a", "LuckPerms", version("v1", "PROJ-A"));

        ReconcileReport report = run();

        assertEquals(1, report.ignored().size());
        assertEquals(0, report.adopted().size(), "otherwise untracking would undo itself every restart");
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("a second jar for a tracked project is flagged rather than silently overwriting it")
    void flagsASecondJarForTheSameProject() {

        onDisk("LuckPerms-5.4.jar", "hash-a", "LuckPerms", version("v1", "PROJ-A"));
        onDisk("LuckPerms-5.6.jar", "hash-b", "LuckPerms", version("v2", "PROJ-A"));

        ReconcileReport report = run();

        assertEquals(1, report.adopted().size());
        assertEquals(1, report.conflicting().size());
        assertTrue(report.needsAttention());
    }

    @Test
    @DisplayName("an untouched folder reports no changes at all")
    void quietWhenNothingHappened() {

        store.put(TrackedPlugin.of(version("v1", "PROJ-A"), "LuckPerms.jar", "hash-a",
                ReleaseChannel.RELEASE, "test"));

        onDisk("LuckPerms.jar", "hash-a", "LuckPerms", version("v1", "PROJ-A"));

        ReconcileReport report = run();

        assertFalse(report.hasChanges(), "so a normal restart can stay silent");
        assertFalse(report.needsAttention());
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("adoption applies configured defaults rather than the hardcoded ones")
    void honoursConfiguredDefaults() {

        reconciler = new Reconciler(store, ignoreList, TrackingDefaults.builder()
                .channel(ReleaseChannel.BETA)
                .autoUpdate(true)
                .soakMinutes(30)
                .build());

        onDisk("LuckPerms.jar", "hash-a", "LuckPerms", version("v1", "PROJ-A"));

        run();

        TrackedPlugin tracked = store.byProjectId("PROJ-A");

        assertEquals(ReleaseChannel.BETA, tracked.channel());
        assertTrue(tracked.autoUpdate());
        assertEquals(30, tracked.soakMinutes());
    }

    @Test
    @DisplayName("with auto-tracking off, recognised jars are reported but not adopted")
    void doesNotAdoptWhenTurnedOff() {

        reconciler = new Reconciler(store, ignoreList, TrackingDefaults.standard(), false);

        onDisk("LuckPerms.jar", "hash-a", "LuckPerms", version("v1", "PROJ-A"));

        ReconcileReport report = run();

        assertEquals(0, report.adopted().size());
        assertEquals(1, report.notAdopted().size(), "known, but deliberately left alone");
        assertEquals(0, report.unknown().size(), "not the same thing as unrecognised");
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("with auto-tracking off, plugins already tracked are still reconciled")
    void stillReconcilesExistingWhenTurnedOff() {

        reconciler = new Reconciler(store, ignoreList, TrackingDefaults.standard(), false);

        store.put(TrackedPlugin.of(version("v1", "PROJ-A"), "LuckPerms.jar", "hash-a",
                ReleaseChannel.RELEASE, "test"));

        ReconcileReport report = run();

        assertEquals(1, report.removed().size(),
                "a jar deleted by hand has to be noticed whatever the setting says");
        assertEquals(0, store.size());
    }

}
