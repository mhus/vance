package de.mhus.vance.brain.damogran;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps file extensions to mime types and render kinds for Damogran
 * import/export and output resolution. Extension-based (the workspace is a
 * plain filesystem — no stored mime), mirroring the R addon's
 * {@code kindForExtension}/{@code mimeForExtension}.
 */
public final class DamogranMime {

    private static final String OCTET_STREAM = "application/octet-stream";

    private static final Map<String, String> MIME_BY_EXT = Map.ofEntries(
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("yaml", "application/x-yaml"),
            Map.entry("yml", "application/x-yaml"),
            Map.entry("xml", "application/xml"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("pdf", "application/pdf"));

    private static final Map<String, String> KIND_BY_EXT = Map.ofEntries(
            Map.entry("md", "markdown"),
            Map.entry("markdown", "markdown"),
            Map.entry("txt", "text"),
            Map.entry("csv", "records"),
            Map.entry("json", "json"),
            Map.entry("yaml", "yaml"),
            Map.entry("yml", "yaml"),
            Map.entry("html", "html"),
            Map.entry("svg", "svg"),
            Map.entry("png", "image"),
            Map.entry("jpg", "image"),
            Map.entry("jpeg", "image"),
            Map.entry("gif", "image"),
            Map.entry("webp", "image"),
            Map.entry("pdf", "pdf"));

    private DamogranMime() {}

    /** Mime type for a path, {@code application/octet-stream} if unknown. */
    public static String mimeForPath(String path) {
        String ext = extension(path);
        return ext == null ? OCTET_STREAM : MIME_BY_EXT.getOrDefault(ext, OCTET_STREAM);
    }

    /** Render kind for a path, or {@code null} to let the client auto-detect. */
    public static @Nullable String kindForPath(String path) {
        String ext = extension(path);
        return ext == null ? null : KIND_BY_EXT.get(ext);
    }

    /**
     * Whether a mime type is textual — used to decide whether an export lands
     * as an editable text document ({@code upsertText}) or a binary document
     * ({@code createOrReplaceBinary}).
     */
    public static boolean isText(String mime) {
        return mime.startsWith("text/")
                || mime.equals("application/json")
                || mime.equals("application/x-yaml")
                || mime.equals("application/xml");
    }

    private static @Nullable String extension(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return null;
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
