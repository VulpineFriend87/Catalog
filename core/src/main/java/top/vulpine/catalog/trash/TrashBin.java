package top.vulpine.catalog.trash;

import top.vulpine.catalog.install.InstallException;
import top.vulpine.catalog.json.Json;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.trash.model.TrashEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Where removed jars go instead of being deleted.
 *
 * <p>The order matters and is not negotiable: the jar is copied into the bin <em>first</em>, and
 * only then is the original deleted. Reading a file is always permitted, even one the JVM has open,
 * so the copy cannot fail for the reason the delete might — and if the delete does fail, nothing
 * has been lost.</p>
 *
 * <p>A failed delete is a normal outcome on Windows, where the running server holds the jar open.
 * It is reported rather than retried, so the caller can arrange for it to happen at shutdown.</p>
 */
public final class TrashBin {

    private final Path directory;

    public TrashBin(Path directory) {
        this.directory = directory;
    }

    /**
     * Moves a jar into the bin.
     *
     * @param jar       the file in the plugins folder
     * @param plugin    what Catalog knew about it, or null if it was never tracked
     * @param removedBy who asked
     * @return what happened, including whether the original is actually gone
     * @throws InstallException if the jar could not be copied into the bin, in which case the
     *                          original is untouched
     */
    public Result bin(Path jar, TrackedPlugin plugin, String removedBy) {

        String fileName = jar.getFileName().toString();
        String storedAs = Instant.now().toEpochMilli() + "-" + fileName;

        Path stored = directory.resolve(storedAs);

        try {
            Files.createDirectories(directory);
            Files.copy(jar, stored, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InstallException("Could not copy " + fileName + " to the trash: "
                    + e.getMessage(), e);
        }

        write(stored, entry(fileName, storedAs, plugin, removedBy));

        boolean deleted;

        try {
            deleted = Files.deleteIfExists(jar);
        } catch (IOException e) {
            deleted = false;
        }

        return new Result(stored, deleted);
    }

    private static TrashEntry entry(String fileName, String storedAs, TrackedPlugin plugin,
                                    String removedBy) {

        TrashEntry.TrashEntryBuilder entry = TrashEntry.builder()
                .fileName(fileName)
                .storedAs(storedAs)
                .removedBy(removedBy)
                .removedAt(Instant.now());

        if (plugin != null) {
            entry.projectId(plugin.projectId())
                    .slug(plugin.slug())
                    .name(plugin.name())
                    .versionId(plugin.versionId())
                    .versionNumber(plugin.versionNumber())
                    .sha512(plugin.sha512());
        }

        return entry.build();
    }

    private static void write(Path stored, TrashEntry entry) {

        Path metadata = stored.resolveSibling(stored.getFileName() + ".json");

        try {
            Files.writeString(metadata, Json.gson().toJson(entry), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The jar is safe, which is the part that cannot be reconstructed. Without the sidecar
            // a restore has to be done by hand, and that is a far smaller loss than refusing here.
        }
    }

    /**
     * The outcome of binning a file.
     *
     * @param stored  where the copy lives
     * @param deleted whether the original is gone, which it is not while the JVM holds it open
     */
    public record Result(Path stored, boolean deleted) {
    }

}
