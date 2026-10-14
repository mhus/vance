package de.mhus.vance.brain.oauthserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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
 *   <li>every address the host resolves to must be public — no loopback,
 *       private, link-local, CGNAT, unique-local, multicast or unspecified
 *       address, so the cluster network ({@code 10.42.…}) is unreachable;</li>
 *   <li>no redirects, a short timeout, a small size cap.</li>
 * </ul>
 * The remaining gap is DNS rebinding between the check and the connect; the
 * response is only ever parsed as client metadata and never echoed, which is
 * what keeps that gap from becoming a read primitive.
 *
 * <p>The document must name itself: {@code client_id} equal to the URL it was
 * fetched from, a {@code client_name}, and at least one acceptable redirect
 * URI. Results are cached for {@link #CACHE_TTL}.
 */
@Component
@Slf4j
public class ClientMetadataDocumentResolver {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_BYTES = 64 * 1024;
    static final int MAX_REDIRECT_URIS = 10;

    private final HttpClient http;
    private final Clock clock;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(ResolvedOAuthClient client, Instant expiresAt) {}

    public ClientMetadataDocumentResolver() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Clock.systemUTC());
    }

    ClientMetadataDocumentResolver(HttpClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
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
        Cached cached = cache.get(clientIdUrl);
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.client();
        }
        URI uri = checkUrl(clientIdUrl);
        checkPublicHost(uri.getHost());
        String body = fetch(uri);
        ResolvedOAuthClient client = parseDocument(clientIdUrl, body);
        cache.put(clientIdUrl, new Cached(client, now.plus(CACHE_TTL)));
        return client;
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
            throw new InvalidClientException("client_id host does not resolve", e);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                log.warn("OAuth CIMD fetch refused: host '{}' resolves to non-public {}", host, address);
                throw new InvalidClientException("client_id host is not a public address");
            }
        }
    }

    /** Test seam for DNS. */
    InetAddress[] resolveHost(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

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
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            // 0.0.0.0/8 and CGNAT 100.64.0.0/10 — not covered by the predicates above.
            return first != 0 && !(first == 100 && second >= 64 && second <= 127);
        }
        if (address instanceof Inet6Address) {
            // Unique local fc00::/7.
            return (b[0] & 0xFE) != 0xFC;
        }
        return true;
    }

    /** Fetch the document body — test seam for the network. */
    String fetch(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new InvalidClientException("client metadata document answered HTTP " + response.statusCode());
                }
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new InvalidClientException("client metadata document is too large");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new InvalidClientException("client metadata document could not be fetched", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidClientException("client metadata document fetch interrupted", e);
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
        String name = text(doc, "client_name");
        if (name == null || name.isBlank()) {
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
        List<String> uris = new ArrayList<>();
        for (JsonNode node : redirects) {
            String uri = node.isString() ? node.asString() : null;
            if (!RedirectUris.isAcceptable(uri)) {
                throw new InvalidClientException("client metadata document lists an unacceptable redirect URI");
            }
            uris.add(uri);
        }
        if (uris.size() > MAX_REDIRECT_URIS) {
            throw new InvalidClientException("client metadata document lists too many redirect URIs");
        }
        return new ResolvedOAuthClient(
                clientIdUrl, OAuthClientKind.CIMD, truncate(name.trim(), 120), text(doc, "client_uri"), uris);
    }

    private static @Nullable String text(JsonNode doc, String field) {
        JsonNode node = doc.get(field);
        return node != null && node.isString() ? node.asString() : null;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
