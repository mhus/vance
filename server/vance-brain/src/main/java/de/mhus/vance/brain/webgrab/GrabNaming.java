package de.mhus.vance.brain.webgrab;

import java.net.URI;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Turning a page into a document path.
 *
 * <p><b>The input is hostile by construction.</b> A grab names its document
 * after the page's own {@code <title>} — a string written by whoever owns the
 * site, arriving over an endpoint that writes to disk. {@code ../../etc/passwd}
 * is the obvious one; a title of {@code .} or {@code ""} or 400 characters of
 * emoji are the ones that actually happen. Everything here exists to make the
 * result a single, boring path segment.
 *
 * <p>Not a general-purpose slugifier: it is deliberately stricter than one, and
 * it never returns something a caller has to check again.
 */
public final class GrabNaming {

    /** Long enough to stay recognisable, short enough for every filesystem. */
    static final int MAX_SLUG_CHARS = 80;

    /** When the page offers no usable name at all. */
    static final String FALLBACK = "untitled";

    private GrabNaming() {}

    /**
     * A safe file-name stem for a page.
     *
     * <p>Order matters: the title is preferred because it is what a person
     * recognises in a folder listing; the URL is the fallback because it always
     * exists. A page with a useless title and no usable URL gets
     * {@link #FALLBACK} — never an empty string, never a name derived from
     * nothing.
     */
    public static String slug(@Nullable String title, @Nullable String url) {
        String fromTitle = slugify(title);
        if (!fromTitle.isEmpty()) return fromTitle;
        String fromUrl = slugify(lastPathSegment(url));
        if (!fromUrl.isEmpty()) return fromUrl;
        String fromHost = slugify(host(url));
        return fromHost.isEmpty() ? FALLBACK : fromHost;
    }

    /**
     * Reduce arbitrary text to {@code [a-z0-9-]}.
     *
     * <p>An allow-list, not a deny-list. A deny-list of dangerous characters is
     * a bet that the list is complete — and the interesting inputs here are
     * exactly the ones nobody thought of. Everything outside the allowed set
     * becomes a separator, so {@code ../../etc/passwd} comes out
     * {@code etc-passwd}: not an escape, not an error, just a name.
     */
    static String slugify(@Nullable String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean pendingSeparator = false;
        for (int i = 0; i < lower.length() && out.length() < MAX_SLUG_CHARS; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (pendingSeparator && !out.isEmpty()) out.append('-');
                pendingSeparator = false;
                out.append(c);
            } else if (c == 'ä' || c == 'ö' || c == 'ü' || c == 'ß') {
                // The four that would otherwise silently disappear from German
                // titles, leaving "bergre" where "übergröße" stood.
                if (pendingSeparator && !out.isEmpty()) out.append('-');
                pendingSeparator = false;
                out.append(switch (c) {
                    case 'ä' -> "ae";
                    case 'ö' -> "oe";
                    case 'ü' -> "ue";
                    default -> "ss";
                });
            } else {
                pendingSeparator = true;
            }
        }
        // Cut at the end rather than trusting the loop bound: a separator is
        // appended before its character, and the umlaut branch writes two — so
        // the last iteration can cross the cap by one or two. Trimming here
        // also guarantees the result never ends on a dash, which the loop
        // condition alone cannot.
        if (out.length() > MAX_SLUG_CHARS) {
            out.setLength(MAX_SLUG_CHARS);
        }
        while (!out.isEmpty() && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /** The extension for a grabbed binary, from its mime type. */
    public static String extensionFor(@Nullable String mimeType) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT).trim();
        int semicolon = mime.indexOf(';');
        if (semicolon >= 0) mime = mime.substring(0, semicolon).trim();
        return switch (mime) {
            case "application/pdf" -> "pdf";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            case "image/avif" -> "avif";
            case "text/markdown" -> "md";
            case "text/plain" -> "txt";
            // Unknown but declared: keep the bytes under a name that does not
            // claim a type. Guessing an extension is how a .exe ends up called
            // .png.
            default -> "bin";
        };
    }

    private static @Nullable String lastPathSegment(@Nullable String url) {
        String path = uri(url) == null ? null : uri(url).getPath();
        if (path == null || path.isBlank()) return null;
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        String segment = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        // Drop a file extension so "post.html" becomes "post" rather than
        // "post-html".
        int dot = segment.lastIndexOf('.');
        return dot > 0 ? segment.substring(0, dot) : segment;
    }

    private static @Nullable String host(@Nullable String url) {
        URI uri = uri(url);
        return uri == null ? null : uri.getHost();
    }

    private static @Nullable URI uri(@Nullable String url) {
        if (url == null || url.isBlank()) return null;
        try {
            return URI.create(url.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
