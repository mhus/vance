package de.mhus.vance.brain.kit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Shared mechanics for kits that arrive as an archive over http.
 *
 * <p>Extracted when the second such loader appeared. The path-traversal
 * check below is the reason it is shared rather than copied: a check that
 * exists twice is a check that will eventually exist in one version.
 */
final class KitArchive {

    private KitArchive() {
    }

    /**
     * Unpack into {@code target}, refusing entries that would land
     * outside it.
     *
     * <p>The archive comes from a remote service. The check is not a
     * comment on that service's trustworthiness — it is that an install
     * writing outside its target directory is unrecoverable, and the
     * check costs one comparison.
     */
    static void unpack(ZipInputStream zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            Path destination = resolveInside(root, entry.getName());
            Files.createDirectories(destination.getParent());
            Files.copy(zip, destination);
        }
    }

    /**
     * Resolve {@code relative} under {@code root}, refusing anything that
     * would land outside it.
     *
     * <p>Two callers with the same requirement: archive entries, and the
     * file list a source declares for rendering. Both strings come from
     * the far end, and both end in a filesystem write.
     */
    static Path resolveInside(Path root, String relative) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new IOException("'" + relative
                    + "' would be written outside the target directory");
        }
        return resolved;
    }

    /** Base urls are configured by hand, so a trailing slash is normal input. */
    static String trimTrailingSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
