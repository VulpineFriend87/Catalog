package top.vulpine.catalog.install;

import top.vulpine.catalog.hash.Hashing;
import top.vulpine.catalog.jar.JarInspector;
import top.vulpine.catalog.jar.model.PluginDescriptor;
import top.vulpine.catalog.modrinth.ModrinthClient;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.modrinth.model.VersionFile;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fetches a version's jar into staging and refuses to hand it back unless it is sound.
 *
 * <p>Nothing downloaded here ever lands in the plugins folder directly. A file reaches staging,
 * gets checked, and only then does a caller move it into place — so a truncated download or a
 * mirror serving the wrong bytes cannot become a plugin that fails to load at boot.</p>
 *
 * <p>Two things are checked, and they answer different questions. The hash and size answer
 * <em>did we get the file Modrinth meant</em>. The bytecode version answers <em>can this JVM run
 * it</em>, which Modrinth cannot tell us at all: no project declares its required Java version, so
 * the only honest source is the class files themselves.</p>
 */
public final class Downloader {

    /** Class file major version 52 is Java 8, and every release since is one more. */
    private static final int JAVA_8_BYTECODE = 52;

    private final ModrinthClient modrinth;
    private final Path staging;

    /**
     * @param modrinth the client to download through
     * @param staging  a directory Catalog owns, emptied on startup
     */
    public Downloader(ModrinthClient modrinth, Path staging) {
        this.modrinth = modrinth;
        this.staging = staging;
    }

    /**
     * Downloads the plugin jar of a version and verifies it.
     *
     * <p>Blocks, so it must be called off the main thread.</p>
     *
     * @param version     the version to fetch
     * @param javaFeature the Java feature release this server runs on
     * @return the staged file, verified
     * @throws InstallException if the file is missing, corrupt, or too new for this JVM
     */
    public Path fetch(ModrinthVersion version, int javaFeature) {

        VersionFile file = version.primaryFile();

        if (file == null || file.url() == null) {
            throw new InstallException("Modrinth has no downloadable file for version "
                    + version.versionNumber() + ".");
        }

        Path target = staging.resolve(file.filename());

        try {
            modrinth.download(file.url(), target).join();
        } catch (Exception e) {
            throw new InstallException("Download failed: " + rootMessage(e), e);
        }

        try {
            verify(target, file);
            verifyRunnable(target, javaFeature);
        } catch (InstallException e) {
            discard(target);
            throw e;
        }

        return target;
    }

    /**
     * Empties the staging directory.
     *
     * <p>Called on startup: anything still in here is the remains of a download that was
     * interrupted, and keeping it would only risk it being mistaken for a finished one.</p>
     */
    public void clean() {

        if (!Files.isDirectory(staging)) {
            return;
        }

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(staging)) {

            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }

        } catch (IOException ignored) {
            // Leftovers are harmless on their own; the next download overwrites its own file.
        }
    }

    private static void verify(Path file, VersionFile expected) {

        long size;
        String hash;

        try {
            size = Files.size(file);
            hash = Hashing.sha512(file);
        } catch (IOException e) {
            throw new InstallException("Could not read the downloaded file: " + e.getMessage(), e);
        }

        if (expected.size() > 0 && size != expected.size()) {
            throw new InstallException("The download is " + size + " bytes, Modrinth says it should be "
                    + expected.size() + ". Discarded.");
        }

        if (expected.sha512() != null && !expected.sha512().equalsIgnoreCase(hash)) {
            throw new InstallException("The download does not match the hash Modrinth published. Discarded.");
        }
    }

    /**
     * Refuses a jar compiled for a newer Java than this server runs.
     *
     * <p>A jar that is too new does not fail politely: the server throws
     * {@code UnsupportedClassVersionError} at boot and the plugin is simply absent. Catching it
     * here turns that into a sentence someone can act on.</p>
     */
    private static void verifyRunnable(Path file, int javaFeature) {

        PluginDescriptor descriptor = JarInspector.inspect(file);
        int major = descriptor.bytecodeMajor();

        // Zero means nothing readable was found, which is not evidence of a problem.
        if (major <= 0) {
            return;
        }

        int required = major - JAVA_8_BYTECODE + 8;

        if (required > javaFeature) {
            throw new InstallException("This build needs Java " + required
                    + " and the server runs Java " + javaFeature + ". Discarded.");
        }
    }

    private static void discard(Path file) {

        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Staging is emptied on the next startup anyway.
        }
    }

    private static String rootMessage(Throwable error) {

        Throwable cause = error;

        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }

        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

}
