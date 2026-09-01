package top.vulpine.catalog.tracking;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import top.vulpine.catalog.json.Json;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes {@code tracked.json}, the record of every plugin Catalog manages.
 *
 * <p>This file is also the lockfile: it holds the project id, version id, hash and file name of
 * everything installed, which is enough to reproduce the same set of plugins somewhere else.</p>
 *
 * <p>Kept in memory as a map keyed by project id, since that is the identity Modrinth guarantees is
 * unique. File names are not unique enough to key on — an operator can rename a jar at any
 * moment.</p>
 */
public final class TrackingStore {

    private static final Type ENTRY_LIST = new TypeToken<List<TrackedPlugin>>() {
    }.getType();

    private final Path file;
    private final Map<String, TrackedPlugin> byProjectId = new LinkedHashMap<>();

    public TrackingStore(Path file) {
        this.file = file;
    }

    /**
     * Loads the file into memory, replacing anything already held.
     *
     * <p>A missing file is normal — it is what a first run looks like — and yields an empty store.
     * A file that exists but cannot be parsed is <em>not</em> treated as empty: that would silently
     * discard every per-plugin setting the operator configured. It is moved aside and reported, so
     * the caller can tell them where it went.</p>
     *
     * @throws TrackingException if the file exists but cannot be read or parsed
     */
    public void load() {

        byProjectId.clear();

        if (!Files.isRegularFile(file)) {
            return;
        }

        String content;

        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TrackingException("Could not read " + file.getFileName(), e);
        }

        if (content.isBlank()) {
            return;
        }

        List<TrackedPlugin> entries;

        try {
            entries = Json.gson().fromJson(content, ENTRY_LIST);
        } catch (JsonSyntaxException e) {
            Path quarantined = quarantine();
            throw new TrackingException("Could not parse " + file.getFileName()
                    + ", it has been moved to " + quarantined.getFileName()
                    + " and per-plugin settings will have to be set again", e);
        }

        if (entries == null) {
            return;
        }

        for (TrackedPlugin entry : entries) {

            // A record with no project id cannot be looked up or updated, so it is not usable state.
            if (entry != null && entry.projectId() != null) {
                byProjectId.put(entry.projectId(), entry);
            }
        }
    }

    /**
     * Writes the store to disk.
     *
     * <p>Written to a temporary file and moved into place. A crash midway through would otherwise
     * leave truncated JSON, which the next load would refuse — losing the settings for every
     * tracked plugin over one bad moment.</p>
     *
     * @throws TrackingException if the file cannot be written
     */
    public void save() {

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, Json.gson().toJson(all(), ENTRY_LIST), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new TrackingException("Could not write " + file.getFileName(), e);
        }
    }

    /**
     * Every tracked plugin, in insertion order.
     *
     * @return the tracked plugins
     */
    public List<TrackedPlugin> all() {
        return new ArrayList<>(byProjectId.values());
    }

    /**
     * @return the tracked plugins keyed by project id, unmodifiable
     */
    public Map<String, TrackedPlugin> byProjectId() {
        return Collections.unmodifiableMap(byProjectId);
    }

    /**
     * @param projectId the Modrinth project id
     * @return the tracked plugin, or null if that project is not tracked
     */
    public TrackedPlugin get(String projectId) {
        return byProjectId.get(projectId);
    }

    /**
     * Finds a tracked plugin by the file it currently occupies.
     *
     * @param fileName the file name in the plugins folder
     * @return the tracked plugin, or null if no record points at that file
     */
    public TrackedPlugin byFileName(String fileName) {

        for (TrackedPlugin tracked : byProjectId.values()) {
            if (fileName.equals(tracked.fileName())) {
                return tracked;
            }
        }

        return null;
    }

    /**
     * Finds a tracked plugin by the hash recorded for it.
     *
     * @param sha512 the hash
     * @return the tracked plugin, or null if no record has that hash
     */
    public TrackedPlugin byHash(String sha512) {

        for (TrackedPlugin tracked : byProjectId.values()) {
            if (sha512.equals(tracked.sha512())) {
                return tracked;
            }
        }

        return null;
    }

    /**
     * Adds or replaces a record.
     *
     * @param tracked the record to store
     */
    public void put(TrackedPlugin tracked) {
        byProjectId.put(tracked.projectId(), tracked);
    }

    /**
     * Stops tracking a project.
     *
     * @param projectId the Modrinth project id
     * @return the record that was removed, or null if it was not tracked
     */
    public TrackedPlugin remove(String projectId) {
        return byProjectId.remove(projectId);
    }

    /**
     * @return how many plugins are tracked
     */
    public int size() {
        return byProjectId.size();
    }

    /**
     * @return the plugins whose update is staged and waiting for a restart
     */
    public Collection<TrackedPlugin> pendingRestart() {

        List<TrackedPlugin> pending = new ArrayList<>();

        for (TrackedPlugin tracked : byProjectId.values()) {
            if (tracked.pendingRestart()) {
                pending.add(tracked);
            }
        }

        return pending;
    }

    private Path quarantine() {

        Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());

        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Reported through the exception the caller is about to receive either way.
        }

        return target;
    }

}
