package de.mhus.vance.brain.oauthserver;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves a {@code client_id} that is an HTTPS URL — a Client ID Metadata
 * Document (draft-ietf-oauth-client-id-metadata-document, MCP authorization
 * spec 2026-07-28).
 *
 * <p>This is the one place the brain fetches a URL a stranger chose, so the
 * fetch is fenced (SSRF):
 * <ul>
 *   <li>HTTPS on the default port only, a path is required;</li>
 *   <li>every address the host resolves to must be public ({@link #isPublic})
 *       — no loopback, private, link-local, CGNAT, unique-local, multicast,
 *       reserved, documentation or translation (NAT64, 6to4, Teredo)
 *       address, so the cluster network ({@code 10.42.…}) is unreachable;</li>
 *   <li>a direct connection — never through a system proxy, which would
 *       resolve the host itself and bypass the address check;</li>
 *   <li>no redirects; a hard deadline for the whole exchange, headers
 *       <i>and</i> body ({@link #TIMEOUT}); a size cap enforced while the
 *       body streams in ({@link #MAX_BYTES});</li>
 *   <li>at most {@link #MAX_CONCURRENT_FETCHES} fetches at once on this pod —
 *       one more is refused immediately rather than queued.</li>
 * </ul>
 * The remaining gap is DNS rebinding between the check and the connect; the
 * response is only ever parsed as client metadata and never echoed, which is
 * what keeps that gap from becoming a read primitive. For the same reason
 * every retrieval failure — unresolvable host, non-public address, HTTP
 * status, timeout, oversize — reports the same message; the detail goes to
 * the trace log only, so the error cannot be used to map the network.
 *
 * <p>The document must name itself: {@code client_id} equal to the URL it was
 * fetched from, a {@code client_name}, and at least one acceptable redirect
 * URI (not on the server's own origin). Results are cached for
 * {@link #CACHE_TTL}, failures for {@link #FAILURE_TTL}; both caches are
 * bounded.
 */
@Component
@Slf4j
public class ClientMetadataDocumentResolver {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);
    static final Duration FAILURE_TTL = Duration.ofSeconds(60);
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_BYTES = 64 * 1024;
    static final int MAX_REDIRECT_URIS = 10;
    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_CONCURRENT_FETCHES = 8;
    static final int CACHE_SIZE = 1_000;
    static final int FAILURE_CACHE_SIZE = 10_000;

    /** The one message for every way the document could not be retrieved. */
    static final String RETRIEVAL_FAILED = "client metadata document could not be retrieved";

    private final OAuthServerSettings settings;
    private final HttpClient http;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final Semaphore fetchSlots = new Semaphore(MAX_CONCURRENT_FETCHES);
    private final Cache<String, ResolvedOAuthClient> cache;
    private final Cache<String, String> failures;

    @Autowired
    public ClientMetadataDocumentResolver(OAuthServerSettings settings) {
        this(
                settings,
                HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .proxy(HttpClient.Builder.NO_PROXY)
                        .build(),
                Ticker.systemTicker());
    }

    ClientMetadataDocumentResolver(OAuthServerSettings settings, HttpClient http, Ticker ticker) {
        this.settings = settings;
        this.http = http;
        this.cache = Caffeine.newBuilder()
                .maximumSize(CACHE_SIZE)
                .expireAfterWrite(CACHE_TTL)
                .ticker(ticker)
                .build();
        this.failures = Caffeine.newBuilder()
                .maximumSize(FAILURE_CACHE_SIZE)
                .expireAfterWrite(FAILURE_TTL)
                .ticker(ticker)
                .build();
    }

    /** Whether {@code clientId} has the shape of a metadata-document client id. */
    public static boolean isDocumentClientId(String clientId) {
        return clientId.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * The client described by the document at {@code clientIdUrl}.
     *
     * @throws InvalidClientException when the URL, the fetch or the document is unacceptable
     */
    public ResolvedOAuthClient resolve(String clientIdUrl) {
        ResolvedOAuthClient cached = cache.getIfPresent(clientIdUrl);
        if (cached != null) {
            return cached;
        }
        String failed = failures.getIfPresent(clientIdUrl);
        if (failed != null) {
            throw new InvalidClientException(failed);
        }
        URI uri = checkUrl(clientIdUrl);
        if (!fetchSlots.tryAcquire()) {
            log.trace("OAuth CIMD fetch of '{}' refused: all {} fetch slots busy", clientIdUrl, MAX_CONCURRENT_FETCHES);
            throw new InvalidClientException("client metadata document lookup is busy, try again later");
        }
        try {
            checkPublicHost(uri.getHost());
            String body = fetch(uri);
            ResolvedOAuthClient client = parseDocument(clientIdUrl, body);
            cache.put(clientIdUrl, client);
            return client;
        } catch (InvalidClientException e) {
            failures.put(clientIdUrl, e.getMessage() == null ? RETRIEVAL_FAILED : e.getMessage());
            throw e;
        } finally {
            fetchSlots.release();
        }
    }

    /** Structural check of the client id URL. */
    static URI checkUrl(String clientIdUrl) {
        URI uri;
        try {
            uri = new URI(clientIdUrl);
        } catch (java.net.URISyntaxException e) {
            throw new InvalidClientException("client_id is not a valid URL", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new InvalidClientException("client_id URL must be https on the default port");
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            throw new InvalidClientException("client_id URL must contain a path");
        }
        return uri;
    }

    /** Every address of {@code host} must be a public one. */
    void checkPublicHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolveHost(host);
        } catch (UnknownHostException e) {
            log.trace("OAuth CIMD fetch refused: host '{}' does not resolve", host);
            throw new InvalidClientException(RETRIEVAL_FAILED, e);
        }
        if (addresses.length == 0) {
            log.trace("OAuth CIMD fetch refused: host '{}' has no address", host);
            throw new InvalidClientException(RETRIEVAL_FAILED);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                log.trace("OAuth CIMD fetch refused: host '{}' resolves to non-public {}", host, address);
                throw new InvalidClientException(RETRIEVAL_FAILED);
            }
        }
    }

    /** Test seam for DNS. */
    InetAddress[] resolveHost(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    /**
     * Whether {@code address} is a globally reachable unicast address.
     *
     * <p>IPv4 is a deny list of the special-purpose ranges (RFC 6890 and
     * successors). IPv6 is an allow list — only global unicast
     * {@code 2000::/3} — minus the ranges inside it that embed or translate
     * to IPv4 (6to4 {@code 2002::/16}, Teredo and the other IETF protocol
     * assignments {@code 2001::/23}) or are documentation
     * ({@code 2001:db8::/32}, {@code 3fff::/20}). Everything else —
     * IPv4-compatible {@code ::/96}, IPv4-mapped, NAT64 {@code 64:ff9b::/96}
     * and {@code 64:ff9b:1::/48}, unique-local, link-local, multicast — lies
     * outside {@code 2000::/3} and is refused by construction.
     */
    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicV4(b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF);
        }
        if (address instanceof Inet6Address) {
            return isPublicV6(b);
        }
        return false;
    }

    private static boolean isPublicV4(int a, int b, int c) {
        return !(a == 0 // "this network" 0.0.0.0/8
                || a == 10 // private
                || (a == 100 && b >= 64 && b <= 127) // CGNAT 100.64.0.0/10
                || a == 127 // loopback
                || (a == 169 && b == 254) // link-local
                || (a == 172 && b >= 16 && b <= 31) // private
                || (a == 192 && b == 0 && c == 0) // IETF protocol assignments 192.0.0.0/24
                || (a == 192 && b == 0 && c == 2) // TEST-NET-1
                || (a == 192 && b == 88 && c == 99) // 6to4 relay anycast
                || (a == 192 && b == 168) // private
                || (a == 198 && (b == 18 || b == 19)) // benchmarking 198.18.0.0/15
                || (a == 198 && b == 51 && c == 100) // TEST-NET-2
                || (a == 203 && b == 0 && c == 113) // TEST-NET-3
                || a >= 224); // multicast 224/4, reserved 240/4, broadcast
    }

    private static boolean isPublicV6(byte[] b) {
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        int third = b[2] & 0xFF;
        int fourth = b[3] & 0xFF;
        if ((first & 0xE0) != 0x20) {
            return false; // outside global unicast 2000::/3
        }
        if (first == 0x20 && second == 0x01 && third <= 0x01) {
            return false; // 2001::/23 — Teredo 2001::/32, ORCHID, other protocol assignments
        }
        if (first == 0x20 && second == 0x01 && third == 0x0D && fourth == 0xB8) {
            return false; // documentation 2001:db8::/32
        }
        if (first == 0x20 && second == 0x02) {
            return false; // 6to4 2002::/16
        }
        return !(first == 0x3F && second == 0xFF && (third & 0xF0) == 0x00); // documentation 3fff::/20
    }

    /**
     * Fetch the document body — test seam for the network.
     *
     * <p>{@link HttpRequest#timeout} only bounds the wait for the response
     * headers, so the whole exchange runs asynchronously under one deadline
     * and is cancelled when it passes. The body is collected by a subscriber
     * that aborts as soon as it exceeds {@link #MAX_BYTES}; a non-200 body is
     * not read at all.
     */
    String fetch(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(
                request,
                info -> info.statusCode() == 200 ? new LimitedBodySubscriber(MAX_BYTES) : new LimitedBodySubscriber(0));
        HttpResponse<byte[]> response;
        try {
            response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            log.trace("OAuth CIMD fetch of '{}' exceeded {}", uri, TIMEOUT);
            throw new InvalidClientException(RETRIEVAL_FAILED, e);
        } catch (ExecutionException e) {
            pending.cancel(true);
            log.trace("OAuth CIMD fetch of '{}' failed: {}", uri, String.valueOf(e.getCause()));
            throw new InvalidClientException(RETRIEVAL_FAILED, e);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new InvalidClientException(RETRIEVAL_FAILED, e);
        }
        if (response.statusCode() != 200) {
            log.trace("OAuth CIMD fetch of '{}' answered HTTP {}", uri, response.statusCode());
            throw new InvalidClientException(RETRIEVAL_FAILED);
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /**
     * Collects at most {@code maxBytes}; one byte more cancels the
     * subscription and fails the body with {@link BodyTooLargeException}.
     * With {@code maxBytes == 0} the subscription is cancelled right away —
     * used for responses whose body is never looked at.
     */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private volatile Flow.@Nullable Subscription subscription;

        LimitedBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (maxBytes == 0) {
                subscription.cancel();
                result.complete(new byte[0]);
                return;
            }
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                int remaining = item.remaining();
                if (buffer.size() + (long) remaining > maxBytes) {
                    cancel();
                    result.completeExceptionally(new BodyTooLargeException());
                    return;
                }
                byte[] chunk = new byte[remaining];
                item.get(chunk);
                buffer.write(chunk, 0, remaining);
            }
            Flow.Subscription current = subscription;
            if (current != null) {
                current.request(1);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }

        private void cancel() {
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
        }
    }

    /** The body exceeded {@link #MAX_BYTES}. */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("client metadata document exceeds " + MAX_BYTES + " bytes");
        }
    }

    /** Validate a fetched document against the URL it was fetched from. */
    ResolvedOAuthClient parseDocument(String clientIdUrl, String body) {
        JsonNode doc;
        try {
            doc = json.readTree(body);
        } catch (RuntimeException e) {
            throw new InvalidClientException("client metadata document is not valid JSON", e);
        }
        if (doc == null || !doc.isObject()) {
            throw new InvalidClientException("client metadata document must be a JSON object");
        }
        if (!clientIdUrl.equals(text(doc, "client_id"))) {
            throw new InvalidClientException("client metadata document does not name its own URL as client_id");
        }
        String name = OAuthClientNames.sanitize(text(doc, "client_name"), MAX_NAME_LENGTH);
        if (name == null) {
            throw new InvalidClientException("client metadata document has no client_name");
        }
        String authMethod = text(doc, "token_endpoint_auth_method");
        if (authMethod != null && !"none".equals(authMethod)) {
            throw new InvalidClientException("only public clients (token_endpoint_auth_method none) are supported");
        }
        JsonNode redirects = doc.get("redirect_uris");
        if (redirects == null || !redirects.isArray() || redirects.isEmpty()) {
            throw new InvalidClientException("client metadata document has no redirect_uris");
        }
        if (redirects.size() > MAX_REDIRECT_URIS) {
            throw new InvalidClientException("client metadata document lists too many redirect URIs");
        }
        String ownOrigin = settings.ownOrigin();
        List<String> uris = new ArrayList<>();
        for (JsonNode node : redirects) {
            String uri = node.isString() ? node.asString() : null;
            if (uri == null || !RedirectUris.isAcceptable(uri, ownOrigin)) {
                throw new InvalidClientException("client metadata document lists an unacceptable redirect URI");
            }
            uris.add(uri);
        }
        return new ResolvedOAuthClient(clientIdUrl, OAuthClientKind.CIMD, name, text(doc, "client_uri"), uris);
    }

    private static @Nullable String text(JsonNode doc, String field) {
        JsonNode node = doc.get(field);
        return node != null && node.isString() ? node.asString() : null;
    }
}
