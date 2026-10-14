package de.mhus.vance.brain.oauthserver;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Validation and matching of OAuth redirect URIs.
 *
 * <p>Allowed shapes: {@code https://…}, or {@code http://} to a loopback host
 * ({@code 127.0.0.1}, {@code [::1]}, {@code localhost}) — the MCP
 * specification's "localhost or HTTPS". No fragment, no user info.
 *
 * <p>A redirect URI on the server's own origin is refused
 * ({@link #isAcceptable(String, String)}): the authorization code would land
 * on a page of this very application, where it does not belong — the
 * login and consent pages included.
 *
 * <p>Matching is <b>exact</b>, with the one exception RFC 8252 §7.3 makes for
 * native apps: on a loopback redirect the port is ignored, because a CLI
 * binds a free port per login and cannot register it in advance. Path and
 * query still have to match.
 */
public final class RedirectUris {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "[::1]", "::1", "localhost");

    /** Upper bound on a single redirect URI — a sanity limit for a stored, displayed value. */
    static final int MAX_LENGTH = 2000;

    private RedirectUris() {}

    /** Whether {@code uri} may be registered as a redirect URI at all. */
    public static boolean isAcceptable(@Nullable String uri) {
        URI parsed = parse(uri);
        if (parsed == null || uri.length() > MAX_LENGTH) {
            return false;
        }
        String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            return true;
        }
        return "http".equals(scheme) && isLoopbackHost(parsed.getHost());
    }

    /**
     * {@link #isAcceptable(String)}, and additionally not on {@code ownOrigin}
     * — the origin of the server's public base URL ({@link #origin(String)}),
     * or {@code null} when it is unknown.
     */
    public static boolean isAcceptable(@Nullable String uri, @Nullable String ownOrigin) {
        if (!isAcceptable(uri)) {
            return false;
        }
        return ownOrigin == null || !ownOrigin.equals(origin(uri));
    }

    /**
     * Normalized origin {@code scheme://host:port} of an absolute URL —
     * lower-case scheme and host, explicit default port — or {@code null} when
     * {@code uri} is not an absolute http(s) URL with a host.
     */
    public static @Nullable String origin(@Nullable String uri) {
        URI parsed = parse(uri);
        if (parsed == null) {
            return null;
        }
        String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
        int port = parsed.getPort();
        if (port == -1) {
            if ("https".equals(scheme)) {
                port = 443;
            } else if ("http".equals(scheme)) {
                port = 80;
            } else {
                return null;
            }
        }
        return scheme + "://" + parsed.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** Whether the redirect goes to the user's own machine. */
    public static boolean isLoopback(String uri) {
        URI parsed = parse(uri);
        return parsed != null && isLoopbackHost(parsed.getHost());
    }

    /** Host shown on the consent page — the one fact about the client the server can vouch for. */
    public static String host(String uri) {
        URI parsed = parse(uri);
        return parsed == null ? "" : parsed.getHost();
    }

    /** Whether {@code presented} is allowed by the {@code registered} redirect URI. */
    public static boolean matches(String registered, String presented) {
        if (registered.equals(presented)) {
            return isAcceptable(presented);
        }
        URI reg = parse(registered);
        URI pre = parse(presented);
        if (reg == null || pre == null || !isAcceptable(presented)) {
            return false;
        }
        if (!"http".equalsIgnoreCase(reg.getScheme())
                || !"http".equalsIgnoreCase(pre.getScheme())
                || !isLoopbackHost(reg.getHost())
                || !reg.getHost().equalsIgnoreCase(pre.getHost())) {
            return false;
        }
        return Objects.equals(emptyToSlash(reg.getRawPath()), emptyToSlash(pre.getRawPath()))
                && Objects.equals(reg.getRawQuery(), pre.getRawQuery());
    }

    private static @Nullable URI parse(@Nullable String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            URI parsed = new URI(uri);
            if (!parsed.isAbsolute()
                    || parsed.getHost() == null
                    || parsed.getRawFragment() != null
                    || parsed.getRawUserInfo() != null) {
                return null;
            }
            return parsed;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** Whether {@code host} names the local machine ({@code 127.0.0.1}, {@code ::1}, {@code localhost}). */
    static boolean isLoopbackHost(@Nullable String host) {
        return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private static String emptyToSlash(@Nullable String path) {
        return path == null || path.isEmpty() ? "/" : path;
    }
}
