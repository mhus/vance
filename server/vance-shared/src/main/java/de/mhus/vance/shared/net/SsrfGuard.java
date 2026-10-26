package de.mhus.vance.shared.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central egress guard against Server-Side Request Forgery (code-review
 * F2). Every outbound fetch driven by LLM- or client-supplied URLs must
 * pass through here so a request cannot be steered at internal services
 * or the cloud metadata endpoint.
 *
 * <p>{@link #assertAllowed(URI)} rejects any non-http(s) scheme and any host
 * that resolves to a refused address. The policy has three layers:
 *
 * <ol>
 *   <li><b>Always refused</b>, not configurable: loopback (the brain itself,
 *       incl. its management port), link-local incl. {@code 169.254.169.254}
 *       (cloud metadata), the unspecified address, multicast and broadcast.</li>
 *   <li><b>Operator deny-list</b> {@code vance.net.ssrf.denied}
 *       ({@link EgressDenyList}): the ranges and hosts a tenant must never
 *       reach — cluster networks, database hosts, nodes.</li>
 *   <li><b>Private networks</b> {@code vance.net.ssrf.private-networks}:
 *       {@code deny} (default) refuses every address that is not globally
 *       reachable unicast ({@link PublicAddresses}); {@code allow} lets tenants
 *       reach private ranges (RFC 1918, CGNAT, ULA) — for installs where the
 *       customer's own network is a legitimate target and the operator lists
 *       what is off limits instead.</li>
 * </ol>
 *
 * <p>IPv6 forms that embed an IPv4 address (IPv4-mapped, IPv4-compatible,
 * NAT64, 6to4) are checked against layers 1 and 2 with the embedded address
 * too, so {@code ::ffff:169.254.169.254} is no way around them.
 * {@link #sendGuarded} additionally re-checks every redirect hop, so a public
 * URL that 302-redirects to an internal target is blocked too — pass it an
 * {@link HttpClient} built with {@link HttpClient.Redirect#NEVER}.
 *
 * <p><b>Residual:</b> DNS rebinding between this check and the socket
 * connect is not fully closed (the JDK client re-resolves on connect and
 * offers no address pinning). The resolve-and-recheck bar matches the
 * review's fix-direction; pin at the network layer if that threat becomes
 * relevant.
 */
public final class SsrfGuard {

    private SsrfGuard() {}

    /** Default max redirect hops followed by {@link #sendGuarded}. */
    public static final int DEFAULT_MAX_REDIRECTS = 5;

    /**
     * Operator escape hatch for local development/testing ONLY. When set, the
     * egress guard stops blocking anything — including loopback and the
     * deny-list — so a dev can point {@code web_fetch}/{@code doc_import_url} at
     * {@code localhost} / a LAN service. Default {@code false} — production must
     * never enable it (it re-opens SSRF). Wired from
     * {@code vance.net.ssrf.allow-private} by {@code SsrfGuardConfigurer} and set
     * only in a non-default profile. It is an operator config, never an
     * LLM-controllable per-call flag, so untrusted input can't reach it.
     */
    private static volatile boolean allowPrivate = false;

    /** Set by {@code SsrfGuardConfigurer} at boot from {@code vance.net.ssrf.allow-private}. */
    public static void setAllowPrivate(boolean allow) {
        allowPrivate = allow;
    }

    /** Current escape-hatch state (for diagnostics/tests). */
    public static boolean isAllowPrivate() {
        return allowPrivate;
    }

    /** Whether tenants may reach non-public (private) address ranges — {@code vance.net.ssrf.private-networks}. */
    public enum PrivateNetworks {
        /** Only globally reachable unicast addresses (the default). */
        DENY,
        /** Private ranges too; the operator's deny-list says what stays off limits. */
        ALLOW;

        /**
         * Parses the property value, case-insensitively.
         *
         * @throws IllegalArgumentException on anything but {@code deny} / {@code allow}
         */
        public static PrivateNetworks parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private static volatile PrivateNetworks privateNetworks = PrivateNetworks.DENY;

    /** Set by {@code SsrfGuardConfigurer} at boot from {@code vance.net.ssrf.private-networks}. */
    public static void setPrivateNetworks(PrivateNetworks mode) {
        privateNetworks = mode;
    }

    /** Operator deny-list ({@code vance.net.ssrf.denied}); applies in every mode. */
    private static volatile EgressDenyList denyList = EgressDenyList.empty();

    /** Set by {@code SsrfGuardConfigurer} at boot from {@code vance.net.ssrf.denied}. */
    public static void setDenyList(EgressDenyList list) {
        denyList = list;
    }

    /** Thrown when a URL is rejected by the egress policy. */
    public static final class SsrfException extends RuntimeException {
        public SsrfException(String message) {
            super(message);
        }

        public SsrfException(String message, @org.jspecify.annotations.Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /** Convenience overload — parses {@code url} then delegates. */
    public static void assertAllowed(String url) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new SsrfException("Malformed URL: " + e.getMessage(), e);
        }
        assertAllowed(uri);
    }

    /**
     * Throws {@link SsrfException} unless {@code uri} is an {@code http(s)}
     * URL whose host resolves exclusively to public unicast addresses.
     */
    public static void assertAllowed(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new SsrfException("Only http:// and https:// URLs are allowed (got scheme '" + scheme + "')");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfException("URL has no host: " + uri);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfException("Host does not resolve: " + host, e);
        }
        for (InetAddress addr : addresses) {
            if (isBlocked(host, addr)) {
                throw new SsrfException("Refusing to fetch a blocked address: " + host + " → " + addr.getHostAddress());
            }
        }
    }

    /** {@code true} for any address a server must not be steered at (see the class doc). */
    static boolean isBlocked(String host, InetAddress addr) {
        // Dev/test escape hatch — operator opted into reaching local/private
        // targets (vance.net.ssrf.allow-private). Never true in production.
        if (allowPrivate) {
            return false;
        }
        EgressDenyList denied = denyList;
        if (denied.deniesHost(host)) {
            return true;
        }
        for (InetAddress form : withEmbeddedIpv4(addr)) {
            if (isAlwaysRefused(form) || denied.deniesAddress(form)) {
                return true;
            }
        }
        return privateNetworks == PrivateNetworks.DENY && !PublicAddresses.isPublic(addr);
    }

    /** Layer 1: targets that are the pod itself or the platform, whatever the mode. */
    private static boolean isAlwaysRefused(InetAddress addr) {
        if (addr.isLoopbackAddress()
                || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        return b.length == 4
                && (b[0] & 0xFF) == 255
                && (b[1] & 0xFF) == 255
                && (b[2] & 0xFF) == 255
                && (b[3] & 0xFF) == 255;
    }

    /**
     * {@code addr} plus the IPv4 address embedded in it, if any: IPv4-mapped
     * {@code ::ffff:a.b.c.d}, IPv4-compatible {@code ::a.b.c.d}, NAT64
     * {@code 64:ff9b::a.b.c.d} and 6to4 {@code 2002:aabb:ccdd::}.
     */
    private static List<InetAddress> withEmbeddedIpv4(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != 16) {
            return List.of(addr);
        }
        byte[] v4 = null;
        if (isZero(b, 0, 10) && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            v4 = new byte[] {b[12], b[13], b[14], b[15]};
        } else if (isZero(b, 0, 12) && !isZero(b, 12, 16)) {
            v4 = new byte[] {b[12], b[13], b[14], b[15]};
        } else if ((b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            v4 = new byte[] {b[12], b[13], b[14], b[15]};
        } else if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
            v4 = new byte[] {b[2], b[3], b[4], b[5]};
        }
        if (v4 == null) {
            return List.of(addr);
        }
        try {
            return List.of(addr, InetAddress.getByAddress(v4));
        } catch (UnknownHostException e) {
            // Four bytes always form a valid address.
            throw new IllegalStateException(e);
        }
    }

    private static boolean isZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sends {@code request} through {@code client}, guarding the initial
     * URL and every redirect hop. {@code client} MUST be configured with
     * {@link HttpClient.Redirect#NEVER} so this method — not the JDK — is
     * the one that follows (and re-checks) redirects. Only {@code GET} and
     * {@code HEAD} redirects are followed (both idempotent and bodyless, so
     * re-issuing them at the new location is safe); a redirect on any other
     * method stops at the 3xx response for the caller to handle.
     */
    public static <T> HttpResponse<T> sendGuarded(
            HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler, int maxRedirects)
            throws IOException, InterruptedException {
        assertAllowed(request.uri());
        HttpResponse<T> response = client.send(request, handler);
        int hops = 0;
        while (isRedirect(response.statusCode()) && hops++ < maxRedirects && isRedirectableMethod(request.method())) {
            Optional<String> location = response.headers().firstValue("location");
            if (location.isEmpty()) break;
            URI next = response.uri().resolve(location.get());
            assertAllowed(next);
            request = redirectRequest(request, next);
            response = client.send(request, handler);
        }
        return response;
    }

    public static <T> HttpResponse<T> sendGuarded(
            HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return sendGuarded(client, request, handler, DEFAULT_MAX_REDIRECTS);
    }

    /** Request headers that carry no credential and may follow a redirect to another origin. */
    private static final Set<String> CROSS_ORIGIN_HEADERS = Set.of("accept", "accept-language", "user-agent");

    /**
     * The follow-up request for a redirect to {@code next}. Within the same
     * origin (scheme, host, port) every header is kept; across origins only
     * {@link #CROSS_ORIGIN_HEADERS} survive, so an {@code Authorization} or API-key
     * header never reaches a host the other side picked.
     */
    static HttpRequest redirectRequest(HttpRequest request, URI next) {
        boolean sameOrigin = sameOrigin(request.uri(), next);
        return HttpRequest.newBuilder(
                        request,
                        (name, value) -> sameOrigin || CROSS_ORIGIN_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
                .uri(next)
                .build();
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isRedirectableMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    /**
     * Wraps {@code delegate} so every request and every redirect hop passes
     * {@link #assertAllowed(URI)}. The delegate must be built with
     * {@link HttpClient.Redirect#NEVER}; with {@code followRedirects} the wrapper
     * follows {@code GET}/{@code HEAD} redirects itself (checking each target),
     * otherwise the 3xx response is returned as is.
     *
     * @throws IllegalArgumentException when the delegate follows redirects itself
     */
    public static HttpClient guard(HttpClient delegate, boolean followRedirects) {
        return new GuardedHttpClient(delegate, followRedirects, SsrfGuard::assertAllowed);
    }

    /** Convenience: a redirect-disabled client suitable for {@link #sendGuarded}. */
    public static HttpClient.Builder guardedClientBuilder() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER);
    }

    /**
     * Default cap for a single outbound response body: 32 MiB. Enough for
     * any page / document a tool legitimately fetches, small enough that a
     * hostile or runaway endpoint can't OOM the pod by streaming forever.
     */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 32L * 1024 * 1024;

    /**
     * Wraps {@code delegate} so the response body is aborted once it exceeds
     * {@code maxBytes} — closes the unbounded-buffering / OOM MEDIUMs on every
     * outbound fetch (web_fetch, link-preview, doc-import, compose HTTP import,
     * …). Works with any {@link HttpResponse.BodyHandler} (string, bytes,
     * input-stream): the byte count is tallied as chunks flow to the delegate
     * subscriber, and on overflow the subscription is cancelled and the body
     * future fails with {@link IOException}. Use as
     * {@code sendGuarded(client, req, SsrfGuard.capped(BodyHandlers.ofString()))}.
     */
    public static <T> HttpResponse.BodyHandler<T> capped(HttpResponse.BodyHandler<T> delegate, long maxBytes) {
        return info -> new LimitingBodySubscriber<>(delegate.apply(info), maxBytes);
    }

    /** {@link #capped(HttpResponse.BodyHandler, long)} with {@link #DEFAULT_MAX_RESPONSE_BYTES}. */
    public static <T> HttpResponse.BodyHandler<T> capped(HttpResponse.BodyHandler<T> delegate) {
        return capped(delegate, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * Byte-counting pass-through subscriber: forwards signals to the delegate
     * until the cumulative body size exceeds the cap, then cancels the
     * subscription and errors the delegate exactly once.
     */
    private static final class LimitingBodySubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> downstream;
        private final long maxBytes;
        private final AtomicLong received = new AtomicLong();
        private Flow.@org.jspecify.annotations.Nullable Subscription subscription;
        private boolean terminated;

        LimitingBodySubscriber(HttpResponse.BodySubscriber<T> downstream, long maxBytes) {
            this.downstream = downstream;
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<T> getBody() {
            return downstream.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription sub) {
            this.subscription = sub;
            downstream.onSubscribe(sub);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            if (terminated) return;
            long total = 0;
            for (ByteBuffer b : item) {
                total += b.remaining();
            }
            if (received.addAndGet(total) > maxBytes) {
                terminated = true;
                if (subscription != null) {
                    subscription.cancel();
                }
                downstream.onError(new IOException("Response body exceeds max size of " + maxBytes + " bytes"));
                return;
            }
            downstream.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            if (terminated) return;
            terminated = true;
            downstream.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (terminated) return;
            terminated = true;
            downstream.onComplete();
        }
    }
}
