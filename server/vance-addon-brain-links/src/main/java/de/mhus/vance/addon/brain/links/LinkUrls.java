package de.mhus.vance.addon.brain.links;

import de.mhus.vance.toolpack.ToolException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The one place that decides what a link <em>is</em> in this app.
 *
 * <p>Two questions, deliberately separated. {@link #normalise} answers
 * "what do we store" — a link a person pasted, cleaned up just enough
 * that the same address typed twice looks the same twice. {@link #identity}
 * answers "is this the same link" — the key the manifest de-duplicates and
 * addresses entries by, and therefore the key the REST surface and the
 * tools pass around.
 *
 * <p>Three decisions worth naming, because each one is a place where
 * being clever would be wrong:
 *
 * <ul>
 *   <li><b>Scheme.</b> Only {@code http} and {@code https} are stored. A
 *       {@code javascript:} or {@code data:} value in a list the browser
 *       renders as clickable cards is an attack on the reader, and the
 *       client's {@code safeUrl} guard is the second line, not the first.
 *       A pasted address with no scheme at all gets {@code https://} — it
 *       is what a person means, and refusing it would make the common
 *       case the error case.</li>
 *   <li><b>The fragment stays.</b> {@code …/guide#chapter-3} and
 *       {@code …/guide} are two different entries. Collapsing them would
 *       make bookmarking a specific section impossible, and "add did
 *       nothing" is the least explicable failure a list can have.</li>
 *   <li><b>The query stays too.</b> Tracking parameters are noise, but
 *       stripping them by guesswork breaks every link whose id lives in
 *       the query. Not our call to make silently.</li>
 * </ul>
 */
public final class LinkUrls {

    private LinkUrls() {}

    /**
     * Clean a pasted address into the form that gets stored.
     *
     * @throws ToolException when it is not an http(s) URL — a refusal the
     *         caller can put in front of the person who typed it.
     */
    public static String normalise(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ToolException("url is required");
        }
        String s = raw.trim();
        // A person pastes "example.com/page", not "https://example.com/page".
        if (!s.contains("://")) {
            if (s.startsWith("//")) {
                s = "https:" + s;
            } else if (s.contains(":")) {
                // Has a scheme-looking prefix but no authority — mailto:,
                // javascript:, data:. Reject rather than repair.
                throw new ToolException("Only http(s) links can be stored — got '" + raw + "'");
            } else {
                s = "https://" + s;
            }
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw new ToolException("'" + raw + "' is not a usable URL: " + e.getReason());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ToolException("Only http(s) links can be stored — got scheme '"
                    + scheme + "'");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ToolException("'" + raw + "' has no host");
        }
        StringBuilder sb = new StringBuilder(scheme).append("://");
        if (uri.getUserInfo() != null) {
            // Credentials in a bookmark are almost always a paste accident,
            // but dropping them silently would change where the link goes.
            sb.append(uri.getUserInfo()).append('@');
        }
        sb.append(uri.getHost().toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        if (port > 0 && !isDefaultPort(scheme, port)) {
            sb.append(':').append(port);
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        sb.append(path);
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            sb.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null && !uri.getRawFragment().isEmpty()) {
            sb.append('#').append(uri.getRawFragment());
        }
        return sb.toString();
    }

    /**
     * The de-duplication and addressing key for an entry.
     *
     * <p>Same as {@link #normalise} today, by intent rather than by
     * accident: an entry is addressed by the URL a person can read off the
     * card, so "remove the link I am looking at" cannot miss. It exists as
     * its own method because the two questions can diverge later (a
     * trailing-slash equivalence, say) and every caller should already be
     * saying which one it is asking.
     */
    public static String identity(@Nullable String raw) {
        return normalise(raw);
    }

    /** Host without a leading {@code www.} — the source label on a card. */
    public static String hostLabel(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return url;
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (URISyntaxException e) {
            return url;
        }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
    }
}
