package de.mhus.vance.shared.net;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Guard for a URL that the server is about to hand to a <em>human</em> — put
 * into a mail body, an inbox item, a rendered card. Server-side twin of
 * {@code client/packages/shared/src/safeUrl.ts}, with the same allow-list.
 *
 * <p><b>Why not {@link SsrfGuard}.</b> That one answers "may <em>we</em> fetch
 * this" and therefore rejects any host resolving to loopback / link-local /
 * site-local. Here the question is a different one: nobody fetches the URL —
 * the recipient's browser does, from wherever they are. A link to an intranet
 * page is a perfectly ordinary thing to show a colleague, so rejecting it would
 * be a false refusal, and the DNS lookup would be paid for nothing. Same
 * strictness about schemes, more permissive about hosts.
 *
 * <p>The allow-list is deliberately identical to the client's: two lists that
 * drift apart are worse than one marginal scheme neither of them needed.
 *
 * <h2>Scheme check, not a URL parse</h2>
 * Only the scheme is examined. Parsing the whole thing with {@link java.net.URI}
 * — which the first version did — makes this class strictly harsher than its
 * twin for reasons that have nothing to do with safety: {@code URI} follows RFC
 * 2396 and rejects a space, a bare {@code %}, {@code |} or {@code [} in the
 * path, while the WHATWG parser every browser and {@code safeUrl.ts} use accepts
 * all of them and percent-encodes them. The result was a share of
 * {@code https://example.com/a[1].pdf} failing with "needs an absolute http,
 * https or mailto URL", which is both a refusal of a legitimate link and a
 * message that says the wrong thing about it.
 *
 * <p>Keeping the strictness on the scheme loses nothing: the dangerous set
 * ({@code javascript:}, {@code data:}, {@code vbscript:}, …) is a property of the
 * scheme alone, and a string that begins with {@code http:}, {@code https:} or
 * {@code mailto:} cannot be reinterpreted as one of them. Anything without a
 * scheme is relative and refused, protocol-relative {@code //host/x} included.
 */
public final class SafeLink {

    private SafeLink() {}

    /**
     * Schemes a link may use. An allow-list, because the dangerous set is not
     * enumerable — {@code javascript:}, {@code data:} and {@code vbscript:} are
     * the known ones and the next will arrive without anyone editing this file.
     */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "mailto");

    /**
     * The scheme production of RFC 3986, anchored: letter, then letters, digits,
     * {@code + - .}, then a colon. Anchoring is the whole point — a tab or a
     * newline inside what looks like a scheme means no match, and therefore a
     * refusal, even though the WHATWG parser would strip the character and end up
     * with {@code javascript:}.
     */
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*):");

    /**
     * ASCII control characters, which no real URL contains. Browsers silently
     * <em>delete</em> tab, CR and LF from a URL, so a string carrying one is
     * displayed as something other than what it resolves to — the display/resolve
     * mismatch a link guard exists to prevent. Refusing them is a deliberate,
     * documented step harsher than {@code safeUrl.ts}, in the safe direction.
     */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");

    /** Characters that end the authority component of a URL. */
    private static final Pattern AUTHORITY_END = Pattern.compile("[/\\\\?#]");

    /** The URL is not something we hand to a human. */
    public static final class UnsafeLinkException extends RuntimeException {
        public UnsafeLinkException(String message) {
            super(message);
        }
    }

    /**
     * The trimmed URL if it is safe to hand on, otherwise {@code null}.
     * Relative URLs are rejected: accepting one would mean resolving it against
     * our own origin, which is exactly the confusion the guard exists to
     * prevent.
     */
    public static @Nullable String safe(@Nullable String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return schemeOf(trimmed) == null ? null : trimmed;
    }

    /**
     * Same as {@link #safe} but names the refusal instead of returning
     * {@code null} — for the write paths, where a rejected URL has to be
     * reported back to whoever supplied it.
     *
     * @throws UnsafeLinkException on a relative URL or a scheme outside the
     *                             allow-list
     */
    public static String require(String raw) {
        String safe = safe(raw);
        if (safe == null) {
            throw new UnsafeLinkException(
                    "Not a shareable link: needs an absolute http, https or mailto URL");
        }
        return safe;
    }

    /**
     * Host of a safe URL, lowercased and without userinfo or port, or
     * {@code null} when there is none (a {@code mailto:} has no host). For audit
     * entries that record where something went without recording the full URL.
     *
     * <p>Parsed by hand rather than through {@link java.net.URI#getHost()}, which
     * returns {@code null} for every authority that is not server-based —
     * {@code https://exa_mple.com/x} (underscore) and {@code https://例え.jp/}
     * (IDN, not punycode) both come back hostless. The audit line would then be
     * missing exactly the field it exists for, and indistinguishably so, since
     * {@code null} is also the legitimate answer for {@code mailto:}.
     */
    public static @Nullable String hostOf(@Nullable String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        String scheme = schemeOf(trimmed);
        if (scheme == null) return null;
        String rest = trimmed.substring(scheme.length() + 1);
        // No "//" means no authority at all: mailto:, and any http URL malformed
        // enough to have dropped it. Either way there is no host to report.
        if (!rest.startsWith("//")) return null;
        String authority = rest.substring(2);
        var end = AUTHORITY_END.matcher(authority);
        if (end.find()) {
            authority = authority.substring(0, end.start());
        }
        // Last '@', matching how browsers split userinfo from host: an earlier
        // one may be part of a userinfo that itself contains an encoded '@'.
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        String host = stripPort(authority);
        return host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
    }

    /** Drops a {@code :port} suffix, leaving a bracketed IPv6 literal intact. */
    private static String stripPort(String authority) {
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close < 0 ? authority : authority.substring(0, close + 1);
        }
        int colon = authority.indexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    /**
     * The lowercased scheme of an already-trimmed URL if it is one we allow,
     * otherwise {@code null} — which covers a relative URL, a control character
     * anywhere in the string, and every scheme outside the allow-list alike.
     */
    private static @Nullable String schemeOf(String trimmed) {
        if (trimmed.isEmpty()) return null;
        if (CONTROL_CHARS.matcher(trimmed).find()) return null;
        var m = SCHEME.matcher(trimmed);
        if (!m.find()) return null;
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        return ALLOWED_SCHEMES.contains(scheme) ? scheme : null;
    }
}
