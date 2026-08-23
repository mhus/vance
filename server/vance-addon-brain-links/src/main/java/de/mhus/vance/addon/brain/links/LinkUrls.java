package de.mhus.vance.addon.brain.links;

import de.mhus.vance.toolpack.ToolException;
import java.net.IDN;
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
 *
 * <p><b>The authority is parsed here rather than by {@link URI}.</b> Two
 * addresses a person really pastes come back from {@code URI.getHost()} as
 * {@code null} — a non-ASCII hostname ({@code https://münchen.de/x}) and a
 * hostname with an underscore in it — because {@code URI} then treats the
 * authority as registry-based. Asking {@code getHost()} and refusing the
 * answer turned "the common case" into "has no host", which is the failure
 * the scheme rule above exists to avoid. So the authority is split by hand
 * and the host run through {@link IDN}: what gets stored is always the
 * punycode form, which is also what makes the same address typed in either
 * spelling the same entry.
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
        //
        // "contains ://" is not the question — a nested URL in a query string
        // ("example.com/out?u=https://x") contains one too, and reading its
        // prefix as the scheme turns an ordinary paste into a refusal. What
        // counts is a scheme token in front of it.
        int schemeEnd = s.indexOf("://");
        if (schemeEnd <= 0 || !isSchemeToken(s.substring(0, schemeEnd))) {
            if (s.startsWith("//")) {
                s = "https:" + s;
            } else if (hasSchemePrefix(s)) {
                // Has a scheme-looking prefix but no authority — mailto:,
                // javascript:, data:. Reject rather than repair.
                throw new ToolException("Only http(s) links can be stored — got '" + raw + "'");
            } else {
                s = "https://" + s;
            }
        }

        int sep = s.indexOf("://");
        String scheme = s.substring(0, sep).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ToolException("Only http(s) links can be stored — got scheme '"
                    + scheme + "'");
        }
        String rest = s.substring(sep + 3);
        int cut = indexOfAny(rest, 0);
        Authority authority = Authority.parse(cut < 0 ? rest : rest.substring(0, cut), raw);
        String tail = cut < 0 ? "" : rest.substring(cut);

        String rendered = authority.render(scheme);
        URI uri;
        try {
            uri = new URI(scheme + "://" + rendered + tail);
        } catch (URISyntaxException e) {
            throw new ToolException("'" + raw + "' is not a usable URL: " + e.getReason());
        }

        StringBuilder sb = new StringBuilder(scheme).append("://").append(rendered);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty()
                ? "/" : uri.getRawPath();
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

    /**
     * Host without a leading {@code www.} — the source label on a card.
     * Punycode is turned back into the spelling the reader typed: the
     * stored form is ASCII so two spellings are one entry, but
     * {@code xn--mnchen-3ya.de} on a card names no page anybody recognises.
     */
    public static String hostLabel(String url) {
        String host;
        try {
            int sep = url.indexOf("://");
            if (sep < 0) return url;
            String rest = url.substring(sep + 3);
            int cut = indexOfAny(rest, 0);
            host = Authority.parse(cut < 0 ? rest : rest.substring(0, cut), url).host();
        } catch (RuntimeException e) {
            return url;
        }
        try {
            host = IDN.toUnicode(host);
        } catch (IllegalArgumentException e) {
            // Keep the ASCII form; a label we cannot decode is still a label.
        }
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /** True when this looks like an http(s) URL — the guard for stored pictures. */
    public static boolean isHttp(@Nullable String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Whether a scheme-less paste carries a scheme after all.
     *
     * <p>The old test was "contains a colon", and it read
     * {@code 192.168.1.5:8080/admin} and {@code localhost:3000/x} — the most
     * likely paste in a list of internal tools — as {@code mailto:}-shaped
     * and refused them. What separates the two is what follows the colon: a
     * port is digits and nothing else.
     */
    private static boolean hasSchemePrefix(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0) return false;
        // "192.168.1.5" and "example.com/r?u=https" are not scheme tokens, so
        // the colon after them is not a scheme colon.
        if (!isSchemeToken(s.substring(0, colon))) return false;
        int end = indexOfAny(s, colon + 1);
        String after = end < 0 ? s.substring(colon + 1) : s.substring(colon + 1, end);
        if (after.isEmpty()) return true;
        for (int i = 0; i < after.length(); i++) {
            if (!Character.isDigit(after.charAt(i))) return true;
        }
        return false;
    }

    /** RFC-3986 scheme grammar: {@code ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )}. */
    private static boolean isSchemeToken(String s) {
        if (s.isEmpty() || !Character.isLetter(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') return false;
        }
        return true;
    }

    /** First index of {@code /}, {@code ?} or {@code #} at or after {@code from}. */
    private static int indexOfAny(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#') return i;
        }
        return -1;
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
    }

    /**
     * The authority of a link, split by hand because {@link URI} refuses to
     * name the host of the two addresses this app most needs to store. The
     * host is held in its punycode form; {@link #hostLabel} turns it back
     * for display.
     */
    private record Authority(@Nullable String userInfo, String host, int port) {

        static Authority parse(String authority, String raw) {
            if (authority.isBlank()) {
                throw new ToolException("'" + raw + "' has no host");
            }
            String userInfo = null;
            String hostPort = authority;
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                // Credentials in a bookmark are almost always a paste
                // accident, but dropping them silently would change where
                // the link goes.
                userInfo = authority.substring(0, at);
                hostPort = authority.substring(at + 1);
            }

            String host;
            String portText = "";
            if (hostPort.startsWith("[")) {
                int close = hostPort.indexOf(']');
                if (close < 0) {
                    throw new ToolException("'" + raw + "' is not a usable URL: unclosed [");
                }
                host = hostPort.substring(0, close + 1);
                String after = hostPort.substring(close + 1);
                if (after.startsWith(":")) portText = after.substring(1);
                else if (!after.isEmpty()) {
                    throw new ToolException("'" + raw + "' is not a usable URL");
                }
            } else {
                int colon = hostPort.lastIndexOf(':');
                if (colon >= 0) {
                    host = hostPort.substring(0, colon);
                    portText = hostPort.substring(colon + 1);
                } else {
                    host = hostPort;
                }
            }
            if (host.isBlank()) {
                throw new ToolException("'" + raw + "' has no host");
            }

            int port = -1;
            if (!portText.isEmpty()) {
                try {
                    port = Integer.parseInt(portText);
                } catch (NumberFormatException e) {
                    throw new ToolException("'" + raw + "' has no usable port");
                }
                if (port < 1 || port > 65535) {
                    throw new ToolException("'" + raw + "' has no usable port");
                }
            }

            if (!host.startsWith("[")) {
                try {
                    host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
                } catch (IllegalArgumentException e) {
                    throw new ToolException("'" + raw + "' has no usable host: " + e.getMessage());
                }
            }
            return new Authority(userInfo, host.toLowerCase(Locale.ROOT), port);
        }

        String render(String scheme) {
            StringBuilder sb = new StringBuilder();
            if (userInfo != null) sb.append(userInfo).append('@');
            sb.append(host);
            if (port > 0 && !isDefaultPort(scheme, port)) sb.append(':').append(port);
            return sb.toString();
        }
    }
}
