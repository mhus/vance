package de.mhus.vance.brain.oauthserver;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Enumeration;
import org.jspecify.annotations.Nullable;

/**
 * The address a request came from, for rate limiting — not for
 * authentication.
 *
 * <p>The brain trusts {@link HttpServletRequest#getRemoteAddr()}, the socket
 * peer, and never a header on its own. In the deployed chain that peer is a
 * proxy (Caddy → face-nginx → brain), so every request would share one
 * address. The forwarding headers are therefore read the way a container's
 * remote-IP valve reads them, with the internal network as the trusted proxy
 * set: as long as the current hop is a non-public address (a proxy in our
 * own network), step one entry to the left in {@code X-Forwarded-For}; the
 * first public address met is the client. A client can only <i>prepend</i>
 * entries — the ones our proxies append sit to the right of them — so a
 * forged entry is never reached while a public address stands in between.
 *
 * <p>Only IP literals are considered; a header entry that is not one stops
 * the walk at the last trusted hop. No entry is ever resolved through DNS.
 */
public final class ClientAddresses {

    private ClientAddresses() {}

    /** The client address of {@code request}, see the class comment. */
    public static String of(HttpServletRequest request) {
        String current = request.getRemoteAddr();
        InetAddress hop = literal(current);
        if (hop == null || ClientMetadataDocumentResolver.isPublic(hop)) {
            return current;
        }
        String[] chain = forwardedChain(request);
        for (int i = chain.length - 1; i >= 0; i--) {
            String candidate = chain[i].trim();
            InetAddress parsed = literal(candidate);
            if (parsed == null) {
                return current;
            }
            current = parsed.getHostAddress();
            if (ClientMetadataDocumentResolver.isPublic(parsed)) {
                return current;
            }
        }
        return current;
    }

    /** All {@code X-Forwarded-For} entries, left to right, across repeated headers. */
    private static String[] forwardedChain(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders("X-Forwarded-For");
        if (headers == null || !headers.hasMoreElements()) {
            return new String[0];
        }
        StringBuilder joined = new StringBuilder();
        while (headers.hasMoreElements()) {
            if (!joined.isEmpty()) {
                joined.append(',');
            }
            joined.append(headers.nextElement());
        }
        return joined.toString().split(",");
    }

    /** {@code value} as an address when it is an IPv4 or IPv6 literal — never a DNS lookup. */
    static @Nullable InetAddress literal(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return null;
        }
        String candidate = value;
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        try {
            return InetAddress.ofLiteral(candidate);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
