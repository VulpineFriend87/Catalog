package top.vulpine.catalog.common.index;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds small jars on disk so the index can be tested without shipping binary fixtures.
 */
final class TestJars {

    private TestJars() {
    }

    static Builder builder(Path directory, String fileName) {
        return new Builder(directory.resolve(fileName));
    }

    static final class Builder {

        private final Path target;
        private final Map<String, byte[]> entries = new LinkedHashMap<>();

        private Builder(Path target) {
            this.target = target;
        }

        /**
         * Adds a descriptor such as {@code plugin.yml} with the given contents.
         */
        Builder descriptor(String name, String contents) {
            entries.put(name, contents.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        /**
         * Adds a class file whose header declares the given major version.
         *
         * <p>Only the eight-byte header matters: the inspector reads the magic number and version
         * and never looks further, so there is no need to synthesise a loadable class.</p>
         */
        Builder classFile(String path, int major) {

            byte[] header = new byte[]{
                    (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                    0, 0,
                    (byte) ((major >> 8) & 0xFF), (byte) (major & 0xFF)
            };

            entries.put(path, header);
            return this;
        }

        /**
         * Adds a file with arbitrary contents.
         */
        Builder file(String path, String contents) {
            entries.put(path, contents.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        Path build() throws IOException {

            Files.createDirectories(target.getParent());

            try (OutputStream out = Files.newOutputStream(target);
                 ZipOutputStream zip = new ZipOutputStream(out)) {

                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }

            return target;
        }

    }

}
