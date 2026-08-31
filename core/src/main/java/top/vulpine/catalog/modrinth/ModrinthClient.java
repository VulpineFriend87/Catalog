package top.vulpine.catalog.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import top.vulpine.catalog.modrinth.http.ApiTransport;
import top.vulpine.catalog.modrinth.model.ModrinthProject;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.catalog.modrinth.model.SearchResults;

import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The Modrinth v2 API, as the rest of Catalog sees it.
 *
 * <p>This class is only the endpoint list: what can be asked, and how each answer is shaped.
 * Everything about sending a request — rate limiting, caching, back-off, threading — lives in
 * {@link ApiTransport}.</p>
 *
 * <p>The two bulk endpoints are what make Catalog viable: {@link #identify(Collection)} turns a
 * folder full of jars into exact project and version identities in one request, and
 * {@link #latest(Collection, List, List, Collection)} answers "is anything out of date" for the
 * whole server in one more.</p>
 */
public final class ModrinthClient implements AutoCloseable {

    private static final String API = "https://api.modrinth.com/v2";

    /**
     * How many hashes to put in a single bulk request. Far above what any real server needs, but
     * chunking means a very large install degrades gracefully instead of being rejected outright.
     */
    private static final int BULK_LIMIT = 500;

    private static final Type VERSION_MAP = new TypeToken<Map<String, ModrinthVersion>>() {
    }.getType();

    private static final Type VERSION_LIST = new TypeToken<List<ModrinthVersion>>() {
    }.getType();

    private static final Type PROJECT_LIST = new TypeToken<List<ModrinthProject>>() {
    }.getType();

    private final ApiTransport transport;

    private ModrinthClient(Builder builder) {
        this.transport = new ApiTransport(API, builder.userAgent, builder.token,
                builder.cacheDirectory, builder.permitsPerMinute, builder.threads);
    }

    public static Builder builder(String userAgent) {
        return new Builder(userAgent);
    }

    /**
     * Identifies files by their SHA-512 hashes.
     *
     * <p>This is how Catalog adopts an existing plugins folder: hash everything, ask once, and know
     * exactly what each jar is. Hashes Modrinth does not recognise are simply absent from the
     * result, which is not an error.</p>
     *
     * @param sha512Hashes the hashes to look up
     * @return a future of hash to the version that file belongs to
     */
    public CompletableFuture<Map<String, ModrinthVersion>> identify(Collection<String> sha512Hashes) {

        return bulk(sha512Hashes, "/version_files", chunk -> {
            JsonObject body = new JsonObject();
            body.add("hashes", array(chunk));
            body.addProperty("algorithm", "sha512");
            return body;
        });
    }

    /**
     * Finds the newest version compatible with this server for each of the given files.
     *
     * <p>Modrinth filters by loader, game version and release channel server-side, so the answer is
     * authoritative and costs one request no matter how many plugins are installed. A hash with no
     * compatible newer version is absent from the result.</p>
     *
     * @param sha512Hashes the hashes of the currently installed files
     * @param loaders      the loaders to accept, e.g. paper, purpur, folia
     * @param gameVersions the Minecraft versions to accept
     * @param channels     the release channels to accept
     * @return a future of hash to the newest matching version
     */
    public CompletableFuture<Map<String, ModrinthVersion>> latest(Collection<String> sha512Hashes,
                                                                  List<String> loaders,
                                                                  List<String> gameVersions,
                                                                  Collection<ReleaseChannel> channels) {

        List<String> channelNames = new ArrayList<>();

        for (ReleaseChannel channel : channels) {
            channelNames.add(channel.apiName());
        }

        return bulk(sha512Hashes, "/version_files/update", chunk -> {
            JsonObject body = new JsonObject();
            body.add("hashes", array(chunk));
            body.addProperty("algorithm", "sha512");
            body.add("loaders", array(loaders));
            body.add("game_versions", array(gameVersions));
            body.add("version_types", array(channelNames));
            return body;
        });
    }

    /**
     * Fetches a project by id or slug.
     *
     * @param idOrSlug the project id or slug
     * @return a future of the project
     */
    public CompletableFuture<ModrinthProject> project(String idOrSlug) {
        return transport.get("/project/" + encode(idOrSlug), ModrinthProject.class);
    }

    /**
     * Fetches several projects at once.
     *
     * @param ids the project ids
     * @return a future of the projects, in whatever order the API returns them
     */
    public CompletableFuture<List<ModrinthProject>> projects(Collection<String> ids) {

        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        return transport.get("/projects?ids=" + encode(array(ids).toString()), PROJECT_LIST);
    }

    /**
     * Lists the versions of a project, optionally narrowed to what this server can run.
     *
     * @param idOrSlug     the project id or slug
     * @param loaders      the loaders to accept, or null for all
     * @param gameVersions the Minecraft versions to accept, or null for all
     * @return a future of the versions, newest first
     */
    public CompletableFuture<List<ModrinthVersion>> versions(String idOrSlug,
                                                             List<String> loaders,
                                                             List<String> gameVersions) {

        StringBuilder query = new StringBuilder();
        appendArrayParam(query, "loaders", loaders);
        appendArrayParam(query, "game_versions", gameVersions);

        return transport.get("/project/" + encode(idOrSlug) + "/version" + query, VERSION_LIST);
    }

    /**
     * Fetches a single version, including its changelog.
     *
     * @param versionId the version id
     * @return a future of the version
     */
    public CompletableFuture<ModrinthVersion> version(String versionId) {
        return transport.get("/version/" + encode(versionId), ModrinthVersion.class);
    }

    /**
     * Searches for projects.
     *
     * @param query  the search text, may be null or empty to browse
     * @param facets the Modrinth facet groups; entries within a group are ORed, groups are ANDed
     * @param limit  how many hits to return
     * @param offset how many hits to skip
     * @return a future of one page of results
     */
    public CompletableFuture<SearchResults> search(String query, List<List<String>> facets, int limit, int offset) {

        StringBuilder path = new StringBuilder("/search?limit=").append(limit).append("&offset=").append(offset);

        if (query != null && !query.isBlank()) {
            path.append("&query=").append(encode(query));
        }

        if (facets != null && !facets.isEmpty()) {

            JsonArray groups = new JsonArray();

            for (List<String> group : facets) {
                groups.add(array(group));
            }

            path.append("&facets=").append(encode(groups.toString()));
        }

        return transport.get(path.toString(), SearchResults.class);
    }

    /**
     * Downloads a file to disk.
     *
     * @param url    the file URL, as given by Modrinth
     * @param target where to write it
     * @return a future of the written file
     */
    public CompletableFuture<Path> download(String url, Path target) {
        return transport.download(url, target);
    }

    @Override
    public void close() {
        transport.close();
    }

    /**
     * Splits a set of hashes across as many requests as needed and merges the results.
     */
    private CompletableFuture<Map<String, ModrinthVersion>> bulk(Collection<String> hashes,
                                                                 String path,
                                                                 Function<List<String>, JsonObject> bodyBuilder) {

        if (hashes.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        List<String> all = new ArrayList<>(hashes);
        List<CompletableFuture<Map<String, ModrinthVersion>>> parts = new ArrayList<>();

        for (int start = 0; start < all.size(); start += BULK_LIMIT) {
            List<String> chunk = all.subList(start, Math.min(start + BULK_LIMIT, all.size()));
            parts.add(transport.post(path, bodyBuilder.apply(chunk), VERSION_MAP));
        }

        return CompletableFuture.allOf(parts.toArray(new CompletableFuture[0])).thenApply(ignored -> {

            Map<String, ModrinthVersion> merged = new HashMap<>();

            for (CompletableFuture<Map<String, ModrinthVersion>> part : parts) {
                merged.putAll(part.join());
            }

            return merged;
        });
    }

    private static void appendArrayParam(StringBuilder query, String name, List<String> values) {

        if (values == null || values.isEmpty()) {
            return;
        }

        query.append(query.length() == 0 ? "?" : "&")
                .append(name)
                .append("=")
                .append(encode(array(values).toString()));
    }

    private static JsonArray array(Collection<String> values) {

        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static final class Builder {

        private final String userAgent;
        private String token;
        private Path cacheDirectory;
        private int permitsPerMinute = 250;
        private int threads = 3;

        private Builder(String userAgent) {
            this.userAgent = userAgent;
        }

        /**
         * A Modrinth personal access token, which raises the rate limit and allows private projects
         * to be read.
         *
         * @param token the token, or null for anonymous access
         * @return this builder
         */
        public Builder token(String token) {
            this.token = token == null || token.isBlank() ? null : token;
            return this;
        }

        /**
         * Where to keep conditional-request responses. Caching is disabled when this is not set.
         *
         * @param directory the cache directory
         * @return this builder
         */
        public Builder cacheDirectory(Path directory) {
            this.cacheDirectory = directory;
            return this;
        }

        /**
         * Requests allowed per minute. Defaults to 250, leaving headroom under Modrinth's 300.
         *
         * @param permits the budget
         * @return this builder
         */
        public Builder permitsPerMinute(int permits) {
            this.permitsPerMinute = permits;
            return this;
        }

        /**
         * How many requests may be in flight at once.
         *
         * @param threads the executor size
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        public ModrinthClient build() {
            return new ModrinthClient(this);
        }

    }

}
