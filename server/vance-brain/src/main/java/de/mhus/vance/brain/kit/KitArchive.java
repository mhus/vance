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
     * Ceiling on what one kit archive may expand to. A kit is a bundle of
     * configuration documents and setting files; a quarter of a gigabyte is
     * already far past anything legitimate, and the number that matters is the
     * one the pod's volume can absorb.
     */
    static final long MAX_UNPACKED_BYTES = 256L * 1024 * 1024;

    /** Ceiling on entry count — a million empty files fill an inode table
     *  without ever approaching the byte budget. */
    static final int MAX_ENTRIES = 20_000;

    /**
     * Unpack into {@code target}, refusing entries that would land
     * outside it — and refusing an archive that is simply too big.
     *
     * <p>The archive comes from a remote service. The checks are not a
     * comment on that service's trustworthiness — it is that an install
     * writing outside its target directory is unrecoverable, and so is a
     * filled-up volume. {@link ZipEntry#getSize} is not consulted: it is the
     * archive's own claim about itself, which is exactly what a zip bomb lies
     * about. The budget is counted from the bytes actually written.
     */
    static void unpack(ZipInputStream zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        ZipEntry entry;
        int entries = 0;
        long written = 0;
        byte[] buffer = new byte[8 * 1024];
        while ((entry = zip.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            if (++entries > MAX_ENTRIES) {
                throw new IOException("kit archive has more than " + MAX_ENTRIES
                        + " entries — refusing to unpack it");
            }
            Path destination = resolveInside(root, entry.getName());
            Files.createDirectories(destination.getParent());
            try (var out = Files.newOutputStream(destination)) {
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    written += read;
                    if (written > MAX_UNPACKED_BYTES) {
                        throw new IOException("kit archive expands beyond "
                                + MAX_UNPACKED_BYTES + " bytes — refusing to unpack it");
                    }
                    out.write(buffer, 0, read);
                }
            }
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
