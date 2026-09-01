package top.vulpine.catalog.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * The single JSON policy for the whole core: one naming convention, one date format, everywhere.
 *
 * <p>Used both to read Modrinth responses and to write Catalog's own state files. Sharing it means
 * a timestamp round-trips identically whether it came from the API or from disk, which matters
 * because publish dates are how Catalog decides what is newer.</p>
 *
 * <p>Deliberately limited to Gson 2.8 features. The runtime Gson is whichever one the platform
 * bundles — Paper 1.18.2 ships 2.8.9 — so records and any newer binding support are unavailable,
 * and every bound class is a plain class bound by field name.</p>
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .create();

    private Json() {
    }

    /**
     * The shared, thread-safe Gson instance for Modrinth payloads.
     *
     * @return the configured Gson
     */
    public static Gson gson() {
        return GSON;
    }

    /**
     * Reads the ISO-8601 timestamps Modrinth returns.
     *
     * <p>Parsed as an offset date-time rather than through {@link Instant#parse}, because the API
     * is not consistent about writing {@code Z} versus an explicit {@code +00:00}.</p>
     */
    private static final class InstantAdapter extends TypeAdapter<Instant> {

        @Override
        public void write(JsonWriter out, Instant value) throws IOException {

            if (value == null) {
                out.nullValue();
                return;
            }

            out.value(value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws IOException {

            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            String text = in.nextString();

            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (Exception e) {
                throw new IOException("Unreadable timestamp from Modrinth: " + text, e);
            }
        }

    }

}
