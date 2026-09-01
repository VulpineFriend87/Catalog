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

    PAPER(List.of("paper"), List.of("spigot", "bukkit")),

    PURPUR(List.of("purpur", "paper"), List.of("spigot", "bukkit")),

    /**
     * Folia is not simply a superset of Paper: it refuses to load any plugin whose descriptor does
     * not declare {@code folia-supported}. The loaders below are still inclusive, because the
     * {@code folia} tag on Modrinth is set per version and is easy for an author to forget, and a
     * missing tag must never silently hide an update that would in fact run. Whether a build really
     * works is settled by reading the downloaded jar, which is the same field Folia itself checks.
     */
    FOLIA(List.of("folia", "paper"), List.of("spigot", "bukkit")),

    VELOCITY(List.of("velocity"), List.of());

    private final List<List<String>> tiers;
    private final List<String> loaders;

    ServerPlatform(List<String> preferred, List<String> fallback) {

        List<List<String>> ladder = new ArrayList<>();
        ladder.add(List.copyOf(preferred));

        if (!fallback.isEmpty()) {
            ladder.add(List.copyOf(fallback));
        }

        this.tiers = Collections.unmodifiableList(ladder);

        List<String> all = new ArrayList<>(preferred);
        all.addAll(fallback);
        this.loaders = Collections.unmodifiableList(all);
    }

    /**
     * The loaders to ask about, most specific group first.
     *
     * <p>Asking for everything at once is wrong when a project publishes a separate build per
     * platform. FastAsyncWorldEdit ships {@code -Paper} and {@code -Bukkit} jars as two versions
     * seconds apart; taking whichever is newest by date would swap a Paper build for a Bukkit one
     * and call it an update. Asking the specific group first, and widening only for plugins it did
     * not answer for, keeps the right variant while still finding plugins published solely for an
     * older platform.</p>
     *
     * @return the groups to try in order
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
