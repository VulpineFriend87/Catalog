package top.vulpine.catalog.jar;

import top.vulpine.catalog.jar.model.InstalledJar;
import top.vulpine.catalog.jar.model.PluginDescriptor;
import top.vulpine.catalog.jar.model.ScanResult;

import top.vulpine.catalog.hash.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Walks the plugins folder and turns it into a list of hashed, inspected jars.
 */
public final class JarScanner {

    private final Path directory;

    public JarScanner(Path pluginsDirectory) {
        this.directory = pluginsDirectory;
    }

    /**
     * Scans the folder from scratch, hashing every jar.
     *
     * @return what was found
     */
    public ScanResult scan() {
        return scan(Collections.emptyList());
    }

    /**
     * Scans the folder, reusing hashes for files that have not changed.
     *
     * @param previous the result of an earlier scan, may be empty
     * @return what was found
     */
    public ScanResult scan(List<InstalledJar> previous) {

        Map<String, InstalledJar> known = new HashMap<>();

        for (InstalledJar jar : previous) {
            known.put(jar.fileName(), jar);
        }

        List<InstalledJar> jars = new ArrayList<>();
        List<InstalledJar> unreadable = new ArrayList<>();

        for (Path file : listJars()) {

            long size;
            long lastModified;

            try {
                size = Files.size(file);
                lastModified = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException e) {
                continue;
            }

            InstalledJar cached = known.get(file.getFileName().toString());

            if (cached != null && cached.matches(size, lastModified)) {
                jars.add(cached);
                continue;
            }

            String hash;

            try {
                hash = Hashing.sha512(file);
            } catch (IOException e) {
                // A jar that cannot be read is reported: on Windows this is usually a lock
                unreadable.add(new InstalledJar(file, size, lastModified, null, PluginDescriptor.unknown()));
                continue;
            }

            jars.add(new InstalledJar(file, size, lastModified, hash, JarInspector.inspect(file)));
        }

        return new ScanResult(jars, unreadable);
    }

    private List<Path> listJars() {

        if (!Files.isDirectory(directory)) {
            return Collections.emptyList();
        }

        try (Stream<Path> entries = Files.list(directory)) {

            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();

        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

}
