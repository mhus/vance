package de.mhus.vance.shared.net;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
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
 */
public final class SafeLink {

    private SafeLink() {}

    /**
     * Schemes a link may use. An allow-list, because the dangerous set is not
     * enumerable — {@code javascript:}, {@code data:} and {@code vbscript:} are
     * the known ones and the next will arrive without anyone editing this file.
     */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "mailto");

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
        if (trimmed.isEmpty()) return null;
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !uri.isAbsolute()) return null;
        return ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)) ? trimmed : null;
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
     * Host of a safe URL, lowercased, or {@code null} when there is none
     * (a {@code mailto:} has no host). For audit entries that record where
     * something went without recording the full URL.
     */
    public static @Nullable String hostOf(@Nullable String raw) {
        String safe = safe(raw);
        if (safe == null) return null;
        try {
            String host = new URI(safe).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
