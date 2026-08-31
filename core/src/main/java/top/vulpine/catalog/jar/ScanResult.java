package top.vulpine.catalog.jar;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of one pass over the plugins folder.
 */
@Getter
@Accessors(fluent = true)
public final class ScanResult {

    private final List<InstalledJar> jars;
    private final List<InstalledJar> unreadable;
    private final List<Duplicate> duplicates;

    ScanResult(List<InstalledJar> jars, List<InstalledJar> unreadable) {
        this.jars = Collections.unmodifiableList(jars);
        this.unreadable = Collections.unmodifiableList(unreadable);
        this.duplicates = findDuplicates(jars);
    }

    /**
     * Looks a jar up by its file name.
     *
     * @param fileName the file name
     * @return the jar, or null if the folder holds no such file
     */
    public InstalledJar byFileName(String fileName) {

        for (InstalledJar jar : jars) {
            if (jar.fileName().equals(fileName)) {
                return jar;
            }
        }

        return null;
    }

    /**
     * Looks a jar up by its hash.
     *
     * @param sha512 the hash
     * @return the jar, or null if nothing in the folder has that hash
     */
    public InstalledJar byHash(String sha512) {

        for (InstalledJar jar : jars) {
            if (sha512.equals(jar.sha512())) {
                return jar;
            }
        }

        return null;
    }

    /**
     * Groups jars that declare the same plugin name.
     *
     * <p>Two jars claiming one plugin is the failure that actually breaks a server, and it is the
     * mistake a stale file name invites: the folder says 5.4, the operator assumes it is old, and
     * drops 5.6 in beside it. Catalog cannot fix this safely on its own — deleting a jar the
     * operator put there by hand is not its call — so it reports it loudly instead.</p>
     */
    private static List<Duplicate> findDuplicates(List<InstalledJar> jars) {

        Map<String, List<InstalledJar>> byName = new LinkedHashMap<>();

        for (InstalledJar jar : jars) {

            if (!jar.info().isPlugin()) {
                continue;
            }

            byName.computeIfAbsent(jar.info().pluginName(), key -> new ArrayList<>()).add(jar);
        }

        List<Duplicate> duplicates = new ArrayList<>();

        byName.forEach((name, group) -> {
            if (group.size() > 1) {
                duplicates.add(new Duplicate(name, Collections.unmodifiableList(group)));
            }
        });

        return Collections.unmodifiableList(duplicates);
    }

    /**
     * Several jars in the plugins folder declaring one plugin name.
     */
    @Getter
    @Accessors(fluent = true)
    public static final class Duplicate {

        private final String pluginName;
        private final List<InstalledJar> jars;

        private Duplicate(String pluginName, List<InstalledJar> jars) {
            this.pluginName = pluginName;
            this.jars = jars;
        }

    }

}
