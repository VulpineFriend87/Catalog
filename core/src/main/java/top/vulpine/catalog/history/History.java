package top.vulpine.catalog.history;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import top.vulpine.catalog.CatalogAction;
import top.vulpine.catalog.json.Json;
import top.vulpine.commons.log.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes {@code history.json}.
 *
 * <p>Newest first, capped, and never allowed to fail an action: a record of what happened is not
 * worth refusing to do the thing it would have recorded.</p>
 */
public final class History {

    private static final Type ENTRY_LIST = new TypeToken<List<HistoryEntry>>() {
    }.getType();

    /** How many entries are kept before the oldest is dropped. */
    public static final int LIMIT = 200;

    private final Path file;
    private final List<HistoryEntry> entries = new ArrayList<>();

    public History(Path file) {
        this.file = file;
    }

    /**
     * Loads the file into memory, replacing anything already held.
     */
    public void load() {

        entries.clear();

        if (!Files.isRegularFile(file)) {
            return;
        }

        String content;

        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.warn(CatalogAction.TRACK, "Could not read " + file.getFileName()
                    + ", history starts empty.");
            return;
        }

        if (content.isBlank()) {
            return;
        }

        try {

            List<HistoryEntry> read = Json.gson().fromJson(content, ENTRY_LIST);

            if (read != null) {
                for (HistoryEntry entry : read) {
                    if (entry != null && entry.at() != null && entry.event() != null) {
                        entries.add(entry);
                    }
                }
            }

        } catch (JsonSyntaxException e) {
            Logger.warn(CatalogAction.TRACK, "Could not parse " + file.getFileName()
                    + ", history starts empty.");
        }
    }

    /**
     * Writes one event down.
     *
     * <p>Failing to record is reported and then dropped. The action it describes has already
     * happened, and undoing it because a log file would not write is worse than losing the line.</p>
     *
     * @param entry what happened
     */
    public void add(HistoryEntry entry) {

        synchronized (entries) {

            entries.add(0, entry);

            while (entries.size() > LIMIT) {
                entries.remove(entries.size() - 1);
            }
        }

        try {
            save();
        } catch (IOException e) {
            Logger.warn(CatalogAction.TRACK, "Could not write " + file.getFileName() + ": "
                    + e.getMessage());
        }
    }

    /**
     * Everything recorded, newest first.
     */
    public List<HistoryEntry> all() {

        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    /**
     * The entries matching both filters, newest first.
     *
     * @param projectId only this plugin, or null for every plugin
     * @param by        only this author, or null for everyone; "catalog" means what it did alone
     * @return the matching entries
     */
    public List<HistoryEntry> filter(String projectId, String by) {

        List<HistoryEntry> matching = new ArrayList<>();
        boolean wantsCatalog = by != null && by.equalsIgnoreCase("catalog");

        for (HistoryEntry entry : all()) {

            if (projectId != null && !projectId.equals(entry.projectId())) {
                continue;
            }

            if (by != null) {

                if (wantsCatalog) {
                    if (entry.byPerson()) {
                        continue;
                    }
                } else if (!entry.byPerson()
                        || !entry.by().toLowerCase(Locale.ROOT).equals(by.toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }

            matching.add(entry);
        }

        return Collections.unmodifiableList(matching);
    }

    /**
     * The project a name, slug or id refers to, among the plugins this log mentions.
     *
     * <p>Looked up here rather than in the tracking file, because the most interesting thing to
     * filter on is often a plugin that is no longer installed.</p>
     *
     * @param query what was typed
     * @return the project id, or null when the log has never mentioned it
     */
    public String projectIdFor(String query) {

        if (query == null || query.isBlank()) {
            return null;
        }

        String wanted = query.trim().toLowerCase(Locale.ROOT);

        for (HistoryEntry entry : all()) {

            if (entry.projectId() == null) {
                continue;
            }

            if (matches(entry.name(), wanted) || matches(entry.slug(), wanted)
                    || matches(entry.projectId(), wanted)) {
                return entry.projectId();
            }
        }

        return null;
    }

    /**
     * Every plugin this log mentions, newest first, for tab completion.
     */
    public List<String> plugins() {

        List<String> names = new ArrayList<>();

        for (HistoryEntry entry : all()) {

            String name = entry.slug() != null ? entry.slug() : entry.name();

            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }

        return names;
    }

    private static boolean matches(String value, String wanted) {
        return value != null && value.toLowerCase(Locale.ROOT).equals(wanted);
    }

    /**
     * @return when the oldest kept entry happened, or null when there are none
     */
    public Instant oldest() {

        List<HistoryEntry> all = all();
        return all.isEmpty() ? null : all.get(all.size() - 1).at();
    }

    private void save() throws IOException {

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

        Files.createDirectories(file.getParent());
        Files.writeString(temporary, Json.gson().toJson(all(), ENTRY_LIST), StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

}
