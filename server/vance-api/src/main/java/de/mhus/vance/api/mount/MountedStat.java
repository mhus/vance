package de.mhus.vance.api.mount;

import de.mhus.vance.api.documents.MountAccess;
import org.jspecify.annotations.Nullable;

/**
 * One entry as the mount reports it — a file or a directory.
 *
 * <p>{@code path} is relative to the mount root and carries no leading
 * slash ({@code "books/dune.pdf"}, not {@code "/books/dune.pdf"} and not
 * {@code "_ext/library/books/dune.pdf"}). The document layer prefixes the
 * namespace; the source never sees it.
 *
 * <p><b>The empty path is the mount root</b>, and it is a legal value. The
 * rest of the contract already uses {@code ""} that way — {@code list("")}
 * lists the root, {@code JaglanPaths.documentPath(mount, "")} is the mount
 * folder — so refusing it here would make the one entry that always exists
 * the one entry that cannot be described.
 *
 * @param path        mount-relative path, no leading or trailing slash;
 *                    empty string for the mount root itself
 * @param directory   {@code true} for a folder — then {@code size} is 0 and
 *                    {@code mimeType} is {@code null}
 * @param size        content length in bytes, 0 for directories and for
 *                    sources that cannot say
 * @param mimeType    the source's claim about the content type; the document
 *                    layer falls back to the file extension when absent
 * @param etag        opaque change token. The one thing that makes
 *                    conditional reads possible on a mounted document, since
 *                    the usual {@code storageId} handle does not exist here.
 * @param modifiedAtMs epoch millis of the last change at the source
 * @param access      what the source allows for this entry — may differ per
 *                    entry inside one mount (a read-only subtree)
 */
public record MountedStat(
        String path,
        boolean directory,
        long size,
        @Nullable String mimeType,
        @Nullable String etag,
        @Nullable Long modifiedAtMs,
        MountAccess access) {

    public MountedStat {
        if (path == null) {
            throw new IllegalArgumentException("path is required (empty means the mount root)");
        }
        // Normalise rather than reject: a source that hands back
        // "/books/" is not misconfigured, it is just a different
        // convention, and every protocol would otherwise re-implement
        // this trim. What falls out to "" is the mount root — legal, and
        // necessarily a directory.
        path = path.strip();
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) {
            directory = true;
        }
        if (size < 0) size = 0;
        if (directory) {
            size = 0;
            mimeType = null;
        }
        if (access == null) access = MountAccess.UNKNOWN;
    }

    /** A directory entry with unknown access — the common listing case. */
    public static MountedStat directory(String path) {
        return new MountedStat(path, true, 0, null, null, null, MountAccess.UNKNOWN);
    }
}
