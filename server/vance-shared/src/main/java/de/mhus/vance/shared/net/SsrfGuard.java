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
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central egress guard against Server-Side Request Forgery (code-review
 * F2). Every outbound fetch driven by LLM- or client-supplied URLs must
 * pass through here so a request cannot be steered at internal services
 * or the cloud metadata endpoint.
 *
 * <p>{@link #assertAllowed(URI)} rejects any non-http(s) scheme and any
 * host that resolves to a loopback / link-local (incl.
 * {@code 169.254.169.254}) / site-local / unique-local / multicast /
 * wildcard address. {@link #sendGuarded} additionally re-checks every
 * redirect hop, so a public URL that 302-redirects to an internal target
 * is blocked too — pass it an {@link HttpClient} built with
 * {@link HttpClient.Redirect#NEVER}.
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

    /** Thrown when a URL is rejected by the egress policy. */
    public static final class SsrfException extends RuntimeException {
        public SsrfException(String message) {
            super(message);
        }
    }

    /** Convenience overload — parses {@code url} then delegates. */
    public static void assertAllowed(String url) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new SsrfException("Malformed URL: " + e.getMessage());
        }
        assertAllowed(uri);
    }

    /**
     * Throws {@link SsrfException} unless {@code uri} is an {@code http(s)}
     * URL whose host resolves exclusively to public unicast addresses.
     */
    public static void assertAllowed(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new SsrfException(
                    "Only http:// and https:// URLs are allowed (got scheme '" + scheme + "')");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfException("URL has no host: " + uri);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfException("Host does not resolve: " + host);
        }
        for (InetAddress addr : addresses) {
            if (isBlocked(addr)) {
                throw new SsrfException(
                        "Refusing to fetch a non-public address: " + host
                                + " → " + addr.getHostAddress());
            }
        }
    }

    /** {@code true} for any address a server must not be steered at. */
    static boolean isBlocked(InetAddress addr) {
        if (addr.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || addr.isAnyLocalAddress()   // 0.0.0.0, ::
                || addr.isLinkLocalAddress()  // 169.254.0.0/16 (incl. metadata), fe80::/10
                || addr.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        // IPv6 unique-local fc00::/7 (isSiteLocalAddress does not cover it).
        if (b.length == 16 && (b[0] & 0xfe) == 0xfc) {
            return true;
        }
        // IPv4-mapped IPv6 (::ffff:a.b.c.d) — re-check the embedded v4.
        if (b.length == 16 && isV4Mapped(b)) {
            byte[] v4 = new byte[] {b[12], b[13], b[14], b[15]};
            try {
                return isBlocked(InetAddress.getByAddress(v4));
            } catch (UnknownHostException e) {
                return true;
            }
        }
        return false;
    }

    private static boolean isV4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return false;
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
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
            HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler,
            int maxRedirects) throws IOException, InterruptedException {
        assertAllowed(request.uri());
        HttpResponse<T> response = client.send(request, handler);
        int hops = 0;
        while (isRedirect(response.statusCode())
                && hops++ < maxRedirects
                && isRedirectableMethod(request.method())) {
            Optional<String> location = response.headers().firstValue("location");
            if (location.isEmpty()) break;
            URI next = response.uri().resolve(location.get());
            assertAllowed(next);
            request = HttpRequest.newBuilder(request, (n, v) -> true).uri(next).build();
            response = client.send(request, handler);
        }
        return response;
    }

    public static <T> HttpResponse<T> sendGuarded(
            HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return sendGuarded(client, request, handler, DEFAULT_MAX_REDIRECTS);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

    private static boolean isRedirectableMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
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
    public static <T> HttpResponse.BodyHandler<T> capped(
            HttpResponse.BodyHandler<T> delegate, long maxBytes) {
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
                downstream.onError(new IOException(
                        "Response body exceeds max size of " + maxBytes + " bytes"));
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
