package top.vulpine.catalog.update.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The kind of server Catalog is running on, and which Modrinth loaders that server can run.
 *
 * <p>Modrinth does not widen loaders on its own: asking for {@code purpur} returns nothing for a
 * plugin published as {@code paper}, even though Purpur runs Paper plugins perfectly well. Since
 * most authors declare only the one platform they build against, asking for a single loader would
 * miss almost everything, so each constant carries itself plus everything it is a superset of.</p>
 */
public enum ServerPlatform {

    PAPER("paper", "spigot", "bukkit"),

    PURPUR("purpur", "paper", "spigot", "bukkit"),

    /**
     * Folia is not simply a superset of Paper: it refuses to load any plugin whose descriptor does
     * not declare {@code folia-supported}. The loaders below are still inclusive, because the
     * {@code folia} tag on Modrinth is set per version and is easy for an author to forget, and a
     * missing tag must never silently hide an update that would in fact run. Whether a build really
     * works is settled by reading the downloaded jar, which is the same field Folia itself checks.
     */
    FOLIA("folia", "paper", "spigot", "bukkit"),

    VELOCITY("velocity");

    private final List<String> loaders;
    private final List<List<String>> tiers;

    ServerPlatform(String... loaders) {

        this.loaders = List.of(loaders);

        List<List<String>> ladder = new ArrayList<>();

        for (String loader : loaders) {
            ladder.add(List.of(loader));
        }

        this.tiers = Collections.unmodifiableList(ladder);
    }

    /**
     * The loaders to ask about, one rung at a time, most specific first.
     *
     * <p>Asking for several at once is wrong when a project publishes a separate build per platform.
     * FastAsyncWorldEdit ships {@code -Paper} and {@code -Bukkit} jars as two versions seconds
     * apart; taking whichever is newest by date would swap a Paper build for a Bukkit one and call
     * it an update. The same applies one rung higher: a plugin built against the Purpur API and
     * also published for Paper must resolve to the Purpur build on a Purpur server, whichever was
     * uploaded last.</p>
     *
     * <p>So each platform gets its own rung, and a wider one is only asked about the plugins the
     * narrower ones did not answer for. That costs a few more requests, all of them small, and is
     * the difference between the right build and a compatible one.</p>
     *
     * @return the loaders to try, in order of preference
     */
    public List<List<String>> loaderTiers() {
        return tiers;
    }

    /**
     * Every loader this server can run, most specific first.
     *
     * @return the flattened ladder
     */
    public List<String> loaders() {
        return loaders;
    }

    /**
     * The loader that names this platform exactly.
     *
     * @return the Modrinth loader id
     */
    public String id() {
        return loaders.get(0);
    }

    /**
     * Whether a version declares this exact platform rather than merely one it is compatible with.
     *
     * <p>Used as a label, never as a filter. On Folia it is worth telling the operator that a build
     * does not claim support, without pretending the update does not exist.</p>
     *
     * @param declared the loaders a Modrinth version declares
     * @return true if the exact platform is named
     */
    public boolean declaredBy(List<String> declared) {
        return declared != null && declared.contains(id());
    }

}
