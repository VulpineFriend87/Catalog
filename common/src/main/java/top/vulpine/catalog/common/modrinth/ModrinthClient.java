package top.vulpine.catalog.common.modrinth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import top.vulpine.catalog.common.modrinth.model.ModrinthProject;
import top.vulpine.catalog.common.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.common.modrinth.model.SearchResults;
import top.vulpine.catalog.common.modrinth.model.VersionType;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * An asynchronous client for the Modrinth v2 API.
 *
 * <p>Every method returns a future and every request runs on this client's own executor, so no call
 * can reach the server main thread.</p>
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

    private static final Gson GSON = ModrinthJson.gson();

    private final HttpClient http;
    private final ExecutorService executor;
    private final RateLimiter limiter;
    private final HttpCache cache;
    private final String userAgent;
    private final String token;

    private ModrinthClient(Builder builder) {

        this.userAgent = builder.userAgent;
        this.token = builder.token;
        this.cache = builder.cacheDirectory == null ? null : new HttpCache(builder.cacheDirectory);
        this.limiter = new RateLimiter(builder.permitsPerMinute);

        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "catalog-modrinth-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };

        this.executor = Executors.newFixedThreadPool(builder.threads, factory);

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static Builder builder(String userAgent) {
        return new Builder(userAgent);
    }

    // --- Endpoints -------------------------------------------------------------------------------

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

        return bulk(sha512Hashes, chunk -> {
            JsonObject body = new JsonObject();
            body.add("hashes", array(chunk));
            body.addProperty("algorithm", "sha512");
            return body;
        }, "/version_files");
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
                                                                  Collection<VersionType> channels) {

        List<String> channelNames = new ArrayList<>();

        for (VersionType channel : channels) {
            channelNames.add(channel.apiName());
        }

        return bulk(sha512Hashes, chunk -> {
            JsonObject body = new JsonObject();
            body.add("hashes", array(chunk));
            body.addProperty("algorithm", "sha512");
            body.add("loaders", array(loaders));
            body.add("game_versions", array(gameVersions));
            body.add("version_types", array(channelNames));
            return body;
        }, "/version_files/update");
    }

    /**
     * Fetches a project by id or slug.
     *
     * @param idOrSlug the project id or slug
     * @return a future of the project
     */
    public CompletableFuture<ModrinthProject> project(String idOrSlug) {
        return get("/project/" + encode(idOrSlug), ModrinthProject.class);
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

        Type type = new TypeToken<List<ModrinthProject>>() {
        }.getType();

        return get("/projects?ids=" + encode(array(ids).toString()), type);
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

        Type type = new TypeToken<List<ModrinthVersion>>() {
        }.getType();

        return get("/project/" + encode(idOrSlug) + "/version" + query, type);
    }

    /**
     * Fetches a single version, including its changelog.
     *
     * @param versionId the version id
     * @return a future of the version
     */
    public CompletableFuture<ModrinthVersion> version(String versionId) {
        return get("/version/" + encode(versionId), ModrinthVersion.class);
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

        return get(path.toString(), SearchResults.class);
    }

    /**
     * Downloads a file to disk.
     *
     * <p>Written to a sibling temporary file and moved into place, so an interrupted download never
     * leaves a half-written jar somewhere that might pick it up. CDN downloads deliberately skip the
     * rate limiter, which exists to protect the API budget.</p>
     *
     * @param url    the file URL, as given by Modrinth
     * @param target where to write it
     * @return a future of the written file
     */
    public CompletableFuture<Path> download(String url, Path target) {

        return CompletableFuture.supplyAsync(() -> {

            Path partial = target.resolveSibling(target.getFileName() + ".part");

            try {
                Files.createDirectories(target.getParent());
                Files.deleteIfExists(partial);

                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", userAgent)
                        .timeout(Duration.ofMinutes(5))
                        .GET()
                        .build();

                HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(partial));

                if (response.statusCode() != 200) {
                    Files.deleteIfExists(partial);
                    throw new ModrinthException("Download failed for " + url, response.statusCode(), null);
                }

                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                return target;

            } catch (IOException e) {
                throw new ModrinthException("Download failed for " + url, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModrinthException("Download interrupted for " + url, e);
            }

        }, executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    // --- Plumbing --------------------------------------------------------------------------------

    /**
     * Splits a set of hashes across as many requests as needed and merges the results.
     */
    private CompletableFuture<Map<String, ModrinthVersion>> bulk(Collection<String> hashes,
                                                                 Function<List<String>, JsonObject> bodyBuilder,
                                                                 String path) {

        if (hashes.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        Type type = new TypeToken<Map<String, ModrinthVersion>>() {
        }.getType();

        List<String> all = new ArrayList<>(hashes);
        List<CompletableFuture<Map<String, ModrinthVersion>>> parts = new ArrayList<>();

        for (int start = 0; start < all.size(); start += BULK_LIMIT) {
            List<String> chunk = all.subList(start, Math.min(start + BULK_LIMIT, all.size()));
            parts.add(post(path, bodyBuilder.apply(chunk), type));
        }

        return CompletableFuture.allOf(parts.toArray(new CompletableFuture[0])).thenApply(ignored -> {

            Map<String, ModrinthVersion> merged = new HashMap<>();

            for (CompletableFuture<Map<String, ModrinthVersion>> part : parts) {
                merged.putAll(part.join());
            }

            return merged;
        });
    }

    private <T> CompletableFuture<T> get(String path, Type type) {

        return CompletableFuture.supplyAsync(() -> {

            String url = API + path;
            HttpCache.Entry cached = cache == null ? null : cache.lookup(url);

            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET();

            if (cached != null) {
                request.header("If-None-Match", cached.etag());
            }

            HttpResponse<String> response = send(request, url);

            if (response.statusCode() == 304 && cached != null) {
                return GSON.<T>fromJson(cached.body(), type);
            }

            if (cache != null) {
                response.headers().firstValue("etag")
                        .ifPresent(etag -> cache.store(url, etag, response.body()));
            }

            return GSON.<T>fromJson(response.body(), type);

        }, executor);
    }

    private <T> CompletableFuture<T> post(String path, JsonObject body, Type type) {

        return CompletableFuture.supplyAsync(() -> {

            String url = API + path;

            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));

            return GSON.<T>fromJson(send(request, url).body(), type);

        }, executor);
    }

    /**
     * Sends a prepared request, honouring the rate limiter and retrying once if Modrinth asks us to
     * slow down.
     */
    private HttpResponse<String> send(HttpRequest.Builder builder, String url) {

        builder.header("User-Agent", userAgent);

        if (token != null) {
            builder.header("Authorization", token);
        }

        for (int attempt = 0; ; attempt++) {

            try {
                limiter.acquire();

                HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status == 429 && attempt == 0) {
                    limiter.backOff(response.headers().firstValueAsLong("retry-after").orElse(10L));
                    continue;
                }

                if (status >= 400) {
                    throw new ModrinthException("Modrinth answered " + status + " for " + url, status, null);
                }

                return response;

            } catch (IOException e) {
                throw new ModrinthException("Could not reach Modrinth at " + url, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModrinthException("Interrupted while calling " + url, e);
            }
        }
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

    // --- Builder ---------------------------------------------------------------------------------

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
         * Where to keep ETag responses. Caching is disabled when this is not set.
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
