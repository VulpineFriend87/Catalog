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
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The projects and files the automatic scan must leave alone.
 *
 * <p>Untracking a plugin has to be remembered, otherwise the scan simply adopts it again on the
 * next boot and the operator's decision is undone by the feature that is supposed to be helpful.</p>
 *
 * <p>Both a project id and a hash are recorded. The project id survives updates, so the plugin
 * stays ignored as new versions appear; the hash catches a jar that Modrinth cannot identify at
 * all, which has no project id to remember.</p>
 */
public final class IgnoreList {

    private static final Type MODEL = new TypeToken<Model>() {
    }.getType();

    private final Path file;
    private Model model = new Model();

    public IgnoreList(Path file) {
        this.file = file;
    }

    /**
     * Loads the list, treating a missing or unreadable file as empty.
     *
     * <p>Unlike the tracking store, failing soft is right here: the worst case is that a plugin the
     * operator untracked gets offered again, which is visible and easily undone. Refusing to start
     * over it would be out of proportion.</p>
     */
    public void load() {

        if (!Files.isRegularFile(file)) {
            model = new Model();
            return;
        }

        try {
            Model loaded = Json.gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), MODEL);
            model = loaded == null ? new Model() : loaded;
        } catch (IOException | JsonSyntaxException e) {
            model = new Model();
        }
    }

    /**
     * Writes the list to disk, atomically.
     *
     * @throws TrackingException if the file cannot be written
     */
    public void save() {

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, Json.gson().toJson(model, MODEL), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new TrackingException("Could not write " + file.getFileName(), e);
        }
    }

    /**
     * Whether the scan should skip a jar.
     *
     * @param projectId the Modrinth project id, or null if unidentified
     * @param sha512    the hash of the file on disk
     * @return true if this jar must not be adopted
     */
    public boolean contains(String projectId, String sha512) {
        return (projectId != null && model.projectIds.contains(projectId))
                || (sha512 != null && model.hashes.contains(sha512));
    }

    /**
     * Adds a jar to the list.
     *
     * @param projectId the Modrinth project id, or null if unidentified
     * @param sha512    the hash of the file on disk
     */
    public void add(String projectId, String sha512) {

        if (projectId != null) {
            model.projectIds.add(projectId);
        }

        if (sha512 != null) {
            model.hashes.add(sha512);
        }
    }

    /**
     * Removes a jar from the list, so the scan may adopt it again.
     *
     * @param projectId the Modrinth project id, or null
     * @param sha512    the hash, or null
     * @return true if anything was actually removed
     */
    public boolean remove(String projectId, String sha512) {

        boolean removed = projectId != null && model.projectIds.remove(projectId);

        if (sha512 != null && model.hashes.remove(sha512)) {
            removed = true;
        }

        return removed;
    }

    /**
     * @return the ignored project ids, unmodifiable
     */
    public Set<String> projectIds() {
        return java.util.Collections.unmodifiableSet(model.projectIds);
    }

    /**
     * @return how many entries are held, counting ids and hashes together
     */
    public int size() {
        return model.projectIds.size() + model.hashes.size();
    }

    /** The on-disk shape, kept separate so the public API is not the file format. */
    private static final class Model {
        private Set<String> projectIds = new LinkedHashSet<>();
        private Set<String> hashes = new LinkedHashSet<>();
    }

}
