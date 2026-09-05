package top.vulpine.catalog.trash;

import top.vulpine.catalog.install.InstallException;
import top.vulpine.catalog.json.Json;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.trash.model.TrashEntry;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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
 *
 * <p>Every removal is stored under its own name, {@code <millis>-<file>}, so removing the same
 * plugin twice keeps both copies and each one can be put back independently. That name is the only
 * identity a restore needs, which is what lets a button offered ten minutes ago still mean the
 * removal it was offered for.</p>
 */
public final class TrashBin {

    private static final String SIDECAR = ".json";

    private final Path directory;
    private final Clock clock;

    public TrashBin(Path directory) {
        this(directory, Clock.systemUTC());
    }

    /**
     * @param directory where removed jars are kept
     * @param clock     what stamps a removal, which a test fixes to force two into one millisecond
     */
    TrashBin(Path directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
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

        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new InstallException("Could not open the trash: " + e.getMessage(), e);
        }

        long millis = clock.millis();
        Path stored;

        // The copy is what claims the name, so nothing can slip between asking whether a name is
        // free and taking it. Removing two plugins inside one millisecond would otherwise give them
        // the same name and let the second quietly replace the first, which is the one thing an
        // undo must never do. Borrowing a millisecond that has not happened yet costs nothing:
        // these are only ever compared to each other.
        while (true) {

            stored = directory.resolve(millis + "-" + fileName);

            try {
                Files.copy(jar, stored);
                break;

            } catch (FileAlreadyExistsException taken) {
                millis++;

            } catch (IOException e) {
                throw new InstallException("Could not copy " + fileName + " to the trash: "
                        + e.getMessage(), e);
            }
        }

        TrashEntry entry = entry(fileName, stored.getFileName().toString(), plugin, removedBy,
                Instant.ofEpochMilli(millis));

        write(stored, entry);

        boolean deleted;

        try {
            deleted = Files.deleteIfExists(jar);
        } catch (IOException e) {
            deleted = false;
        }

        return new Result(entry, stored, deleted);
    }

    /**
     * Everything currently in the bin, newest removal first.
     *
     * <p>The jars are what is listed, not the sidecars: a jar whose metadata failed to write is
     * still a jar somebody may want back, and it can be described well enough from its own name to
     * be worth offering.</p>
     *
     * @return the entries, or an empty list if the bin has never been used
     */
    public List<TrashEntry> list() {

        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        List<TrashEntry> entries = new ArrayList<>();

        try (Stream<Path> files = Files.list(directory)) {

            files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .forEach(path -> entries.add(read(path)));

        } catch (IOException e) {
            return List.of();
        }

        entries.sort(Comparator.comparing(TrashEntry::removedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        return entries;
    }

    /**
     * One entry by the name it is stored under.
     *
     * @param storedAs the unique name from {@link TrashEntry#storedAs()}
     * @return the entry, or null if it is no longer in the bin
     */
    public TrashEntry find(String storedAs) {

        if (storedAs == null || storedAs.contains("/") || storedAs.contains("\\")) {
            return null;
        }

        Path stored = directory.resolve(storedAs);

        return Files.isRegularFile(stored) ? read(stored) : null;
    }

    /**
     * Puts a jar back where it came from and drops it from the bin.
     *
     * <p>The copy happens before the bin is cleaned up, for the same reason removal copies before
     * deleting: an interrupted restore should leave the file recoverable rather than gone.</p>
     *
     * @param entry  what to restore
     * @param target where the jar should end up, which is the plugins folder
     * @throws InstallException if the target already exists or the copy fails
     */
    public void restore(TrashEntry entry, Path target) {

        Path stored = directory.resolve(entry.storedAs());

        if (!Files.isRegularFile(stored)) {
            throw new InstallException(entry.fileName() + " is no longer in the trash.");
        }

        if (Files.exists(target)) {
            throw new InstallException(target.getFileName() + " is already in the plugins folder.");
        }

        try {
            Files.copy(stored, target);
        } catch (IOException e) {
            throw new InstallException("Could not restore " + entry.fileName() + ": "
                    + e.getMessage(), e);
        }

        discard(entry);
    }

    /**
     * Deletes one entry from the bin permanently.
     *
     * @param entry what to delete
     */
    public void discard(TrashEntry entry) {

        Path stored = directory.resolve(entry.storedAs());

        try {
            Files.deleteIfExists(stored);
            Files.deleteIfExists(stored.resolveSibling(stored.getFileName() + SIDECAR));
        } catch (IOException ignored) {
            // Leaving a file behind costs disk and nothing else; failing the caller would cost
            // them the action they actually asked for.
        }
    }

    /**
     * Deletes every removal in the bin permanently.
     *
     * @return how many were deleted
     */
    public int empty() {

        int deleted = 0;

        for (TrashEntry entry : list()) {
            discard(entry);
            deleted++;
        }

        return deleted;
    }

    /**
     * Deletes everything removed longer ago than the retention window.
     *
     * <p>Run at startup rather than on a timer: the bin only grows when somebody removes a plugin,
     * and nothing about an old entry becomes urgent between two restarts.</p>
     *
     * @param retention how long to keep a removal, or zero to keep everything forever
     * @param now       the moment to measure against
     * @return how many entries were deleted
     */
    public int prune(Duration retention, Instant now) {

        if (retention == null || retention.isZero() || retention.isNegative()) {
            return 0;
        }

        Instant cutoff = now.minus(retention);
        int deleted = 0;

        for (TrashEntry entry : list()) {

            if (entry.removedAt() != null && entry.removedAt().isBefore(cutoff)) {
                discard(entry);
                deleted++;
            }
        }

        return deleted;
    }

    /**
     * Reads an entry's metadata, falling back to what the stored name alone can say.
     *
     * <p>The sidecar is written on a best-effort basis, so it can legitimately be missing. The name
     * still carries the removal time and the original file name, which is everything a restore
     * needs — only the Modrinth identity is lost, and that is recovered by the next startup scan
     * anyway.</p>
     */
    private TrashEntry read(Path stored) {

        String storedAs = stored.getFileName().toString();
        Path metadata = stored.resolveSibling(storedAs + SIDECAR);

        if (Files.isRegularFile(metadata)) {

            try {
                TrashEntry entry = Json.gson().fromJson(
                        Files.readString(metadata, StandardCharsets.UTF_8), TrashEntry.class);

                if (entry != null && entry.storedAs() != null) {
                    return entry;
                }

            } catch (Exception ignored) {
                // Falls through to the name, which cannot be corrupt because it is the file itself.
            }
        }

        return fromName(storedAs);
    }

    private static TrashEntry fromName(String storedAs) {

        int split = storedAs.indexOf('-');
        String fileName = split < 0 ? storedAs : storedAs.substring(split + 1);
        Instant removedAt = null;

        if (split > 0) {

            try {
                removedAt = Instant.ofEpochMilli(Long.parseLong(storedAs.substring(0, split)));
            } catch (NumberFormatException ignored) {
                // Named by hand rather than by us. It still restores; it just has no date.
            }
        }

        return TrashEntry.builder()
                .fileName(fileName)
                .storedAs(storedAs)
                .removedAt(removedAt)
                .build();
    }

    private static TrashEntry entry(String fileName, String storedAs, TrackedPlugin plugin,
                                    String removedBy, Instant removedAt) {

        TrashEntry.TrashEntryBuilder entry = TrashEntry.builder()
                .fileName(fileName)
                .storedAs(storedAs)
                .removedBy(removedBy)
                .removedAt(removedAt);

        if (plugin != null) {
            entry.projectId(plugin.projectId())
                    .slug(plugin.slug())
                    .name(plugin.name())
                    .versionId(plugin.versionId())
                    .versionNumber(plugin.versionNumber())
                    .sha512(plugin.sha512())
                    .channel(plugin.channel());
        }

        return entry.build();
    }

    private static void write(Path stored, TrashEntry entry) {

        Path metadata = stored.resolveSibling(stored.getFileName() + SIDECAR);

        try {
            Files.writeString(metadata, Json.gson().toJson(entry), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The jar is safe, which is the part that cannot be reconstructed. Without the sidecar
            // a restore loses the Modrinth identity and nothing else.
        }
    }

    /**
     * The outcome of binning a file.
     *
     * @param entry   what was written, whose {@code storedAs} identifies this exact removal
     * @param stored  where the copy lives
     * @param deleted whether the original is gone, which it is not while the JVM holds it open
     */
    public record Result(TrashEntry entry, Path stored, boolean deleted) {
    }

}
