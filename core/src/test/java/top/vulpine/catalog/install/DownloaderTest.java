package top.vulpine.catalog.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.vulpine.catalog.json.Json;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Downloader")
class DownloaderTest {

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"LuckPerms-Bukkit-5.5.71.jar", "ViaBackwards-5.12.1-SNAPSHOT.jar", "a b.JAR"})
    @DisplayName("accepts a plain jar name")
    void plain(String name) {
        assertTrue(Downloader.isPlainJarName(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../server.jar", "..\\server.jar", "plugins/x.jar", "C:x.jar", ".hidden.jar",
            "notes.txt", "x.jar.exe", "", " "})
    @DisplayName("refuses anything that is not a plain jar name")
    void unsafe(String name) {
        assertFalse(Downloader.isPlainJarName(name));
    }

    @Test
    @DisplayName("refuses an unsafe name before downloading anything")
    void unsafeNameNotDownloaded() throws IOException {

        Path staging = Files.createDirectories(root.resolve("staging"));
        Downloader downloader = new Downloader(null, staging);

        assertThrows(InstallException.class, () -> downloader.fetch(version("../../server.jar", "ab12"), 21));
        assertTrue(isEmpty(staging));
    }

    @Test
    @DisplayName("refuses a file Modrinth lists no hash for")
    void missingHashNotDownloaded() throws IOException {

        Path staging = Files.createDirectories(root.resolve("staging"));
        Downloader downloader = new Downloader(null, staging);

        assertThrows(InstallException.class, () -> downloader.fetch(version("LuckPerms.jar", null), 21));
        assertTrue(isEmpty(staging));
    }

    private static boolean isEmpty(Path folder) throws IOException {
        try (var entries = Files.list(folder)) {
            return entries.findAny().isEmpty();
        }
    }

    private static ModrinthVersion version(String fileName, String sha512) {
        return Json.gson().fromJson("""
                {
                  "id": "abc12345",
                  "project_id": "PROJ0001",
                  "version_number": "1.0",
                  "version_type": "release",
                  "date_published": "2026-08-08T10:00:00Z",
                  "loaders": ["paper"],
                  "game_versions": ["1.21.4"],
                  "files": [{
                    "url": "https://cdn.modrinth.com/data/x/y.jar",
                    "filename": %s,
                    "primary": true,
                    "size": 10,
                    "hashes": {%s}
                  }],
                  "dependencies": []
                }
                """.formatted(Json.gson().toJson(fileName),
                sha512 == null ? "" : "\"sha512\": \"" + sha512 + "\""), ModrinthVersion.class);
    }

}
