package de.mhus.vance.backup.fs;

import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;

/**
 * Maps a Vance document path to a safe file path on the local file
 * system — handling collisions, escaping, and file-vs-folder
 * conflicts from {@code planning/user-backup.md} §4.5.
 *
 * <h2>Rules</h2>
 *
 * <ol>
 *   <li><b>Empty path → {@code "_"}</b>. A document named the empty
 *       string would round-trip as nothing on the wire; we map it to
 *       a single underscore so the user sees a concrete file.
 *   <li><b>Windows-illegal characters</b> {@code <>:"|?*} and control
 *       bytes (0x00–0x1F) are replaced with {@code %XX} (the hex of
 *       the UTF-8 byte). The Windows reserved device names
 *       {@code CON}, {@code PRN}, {@code AUX}, {@code NUL}, and the
 *       {@code COM1}–{@code COM9}, {@code LPT1}–{@code LPT9} families
 *       are prefixed with {@code _} when they appear as a complete
 *       segment.
 *   <li><b>Trailing dots and spaces</b> are stripped from each
 *       segment (Windows treats {@code foo.} as {@code foo}).
 *   <li><b>Segments starting with a dot</b> keep the dot — the user
 *       explicitly created a hidden folder.
 *   <li><b>Slashes inside a segment</b> are illegal on the wire (the
 *       server sends slash-separated segments); if one slips through
 *       it is treated as a directory separator by us, too — we do
 *       not embed raw slashes inside a single segment.
 *   <li><b>Collision</b>: if mapping {@code foo.md} lands on a path
 *       that already exists in this snapshot, we append {@code ~N}
 *       before the extension ({@code foo~2.md}) so the original is
 *       never overwritten. The manifest carries the mapping so a
 *       later restore knows which file is which.
 *   <li><b>File-vs-folder</b>: a document {@code a} that lives next
 *       to documents under {@code a/...} would map to {@code a}
 *       (file) and {@code a/...} (folder). The file wins, the folder
 *       contents get a {@code ~dir} suffix on the segment so the
 *       shape stays unambiguous on disk.
 * </ol>
 *
 * <h2>Single-source-of-truth</h2>
 *
 * <p>The mapping is deterministic from the input path alone — two
 * runs on the same input produce the same output (before the
 * collision suffix is applied). That's what makes
 * {@code --manifest-only} verifications possible.
 *
 * <p>This class does not consult the file system. It transforms
 * strings; collision detection is the caller's job (they ask us
 * "if this path already exists, give me a free name").
 */
@NullMarked
public final class PathMapper {

    /** Suffix used when an original path collides with one already on disk. */
    public static final String COLLISION_INFIX = "~";

    /** Suffix used when a document conflicts with a folder of the same name. */
    public static final String FOLDER_CONFLICT_SUFFIX = "~dir";

    private PathMapper() {}

    /**
     * Maps a Vance document path to a safe relative path under the
     * snapshot root. The result never starts with a slash; segment
     * separators are platform-appropriate (forward slashes on every
     * platform — the host OS handles them).
     */
    public static String map(String documentPath) {
        if (documentPath.isEmpty()) {
            return "_";
        }
        String[] rawSegments = documentPath.split("/", -1);
        StringBuilder out = new StringBuilder(documentPath.length());
        for (int i = 0; i < rawSegments.length; i++) {
            String segment = rawSegments[i];
            String mapped = mapSegment(segment);
            // Trailing empty segment means the path ends with a slash
            // — preserve that as a directory marker so a document
            // path like "foo/" doesn't collapse to "foo".
            if (i > 0) {
                out.append('/');
            }
            if (segment.isEmpty()) {
                out.append("_");
            } else {
                out.append(mapped);
            }
        }
        return out.toString();
    }

    /**
     * Applies only the collision-suffix logic. Given a candidate
     * {@code target} (a relative path inside the snapshot), return
     * the next free variant. The caller has to decide whether the
     * path already exists; this method just generates the names
     * {@code a.md}, {@code a~2.md}, {@code a~3.md}, ... until the
     * caller signals "ok, use this one".
     *
     * <p>{@code attempt} is the 1-based attempt counter: {@code 1}
     * for the original (no suffix), {@code 2} for {@code ~2}, etc.
     * Pass {@code 1} first; if the file already exists, call again
     * with {@code 2}, and so on.
     */
    public static String collisionVariant(String target, int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, got " + attempt);
        }
        if (attempt == 1) {
            return target;
        }
        int dot = target.lastIndexOf('.');
        int slash = target.lastIndexOf('/');
        // Only treat the dot as an extension separator when it
        // appears after the last slash — a folder called
        // "foo.bar/baz" keeps the dot in the folder name.
        if (dot > slash) {
            String stem = target.substring(0, dot);
            String ext = target.substring(dot); // includes the dot
            return stem + COLLISION_INFIX + attempt + ext;
        }
        return target + COLLISION_INFIX + attempt;
    }

    /**
     * Returns the suffix used to disambiguate a document that
     * collides with a folder of the same name
     * ({@link #FOLDER_CONFLICT_SUFFIX}).
     */
    public static String folderConflictSuffix() {
        return FOLDER_CONFLICT_SUFFIX;
    }

    /**
     * Translates one path segment into something the host file
     * system accepts. Public for tests.
     */
    static String mapSegment(String segment) {
        if (segment.isEmpty()) {
            return "_";
        }
        // Strip trailing dots and spaces (Windows quirk).
        String trimmed = stripTrailingDotsAndSpaces(segment);
        if (trimmed.isEmpty()) {
            return "_";
        }
        // Replace illegal characters with %XX (hex of UTF-8 bytes).
        // Multibyte UTF-8 sequences are escaped too so the round-trip
        // is bit-stable across platforms — a Mac APFS volume mounted
        // on a Linux NFS share sees the same byte sequence.
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (isIllegalChar(c) || c > 0x7F) {
                for (byte b : String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    out.append('%');
                    out.append(String.format("%02X", b & 0xFF));
                }
            } else if (c == '/') {
                // Slashes inside a segment — replace with %2F so the
                // segment round-trips as a single path component.
                out.append("%2F");
            } else {
                out.append(c);
            }
        }
        String mapped = out.toString();
        // Reserved device name on Windows? Prefix with underscore.
        // Check the original (pre-extension) stem too: "CON.md" is
        // NOT reserved (Windows allows the extension), but "CON" is.
        if (isWindowsReserved(mapped)) {
            mapped = "_" + mapped;
        }
        return mapped;
    }

    private static String stripTrailingDotsAndSpaces(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    private static boolean isIllegalChar(char c) {
        return c == '<' || c == '>' || c == ':' || c == '"' || c == '|' || c == '?' || c == '*' || (c >= 0 && c < 0x20);
    }

    private static boolean isWindowsReserved(String segment) {
        // Reserved even with an extension on Windows: CON, PRN, AUX, NUL.
        // COM1..COM9, LPT1..LPT9 — Windows accepts "CON.txt" but
        // not "CON" alone; we treat the bare name as reserved.
        String upper = segment.toUpperCase(java.util.Locale.ROOT);
        return upper.equals("CON")
                || upper.equals("PRN")
                || upper.equals("AUX")
                || upper.equals("NUL")
                || (upper.length() == 4
                        && (upper.startsWith("COM") || upper.startsWith("LPT"))
                        && upper.charAt(3) >= '1'
                        && upper.charAt(3) <= '9');
    }

    /**
     * Returns true if a path string ends with a slash — a directory
     * marker in Vance document paths.
     */
    public static boolean isDirectoryPath(String path) {
        return path.endsWith("/");
    }

    /**
     * Resolves a target path against a snapshot root, returning the
     * absolute {@link Path}. Convenience so callers don't import
     * {@link Path} twice.
     */
    public static Path resolveAgainst(Path root, String relative) {
        return root.resolve(relative);
    }
}
