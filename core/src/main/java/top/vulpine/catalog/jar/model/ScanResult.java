package top.vulpine.catalog.jar.model;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.List;

/**
 * The outcome of one pass over the plugins folder.
 */
@Getter
@Accessors(fluent = true)
public final class ScanResult {

    private final List<InstalledJar> jars;
    private final List<InstalledJar> unreadable;

    public ScanResult(List<InstalledJar> jars, List<InstalledJar> unreadable) {
        this.jars = Collections.unmodifiableList(jars);
        this.unreadable = Collections.unmodifiableList(unreadable);
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

}
