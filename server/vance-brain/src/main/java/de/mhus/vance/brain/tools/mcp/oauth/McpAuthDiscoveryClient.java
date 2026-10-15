package de.mhus.vance.brain.tools.mcp.oauth;

import de.mhus.vance.brain.oauthserver.ClientMetadataDocumentResolver;
import de.mhus.vance.toolpack.core.PackHttpClient;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbound HTTP of the MCP authorization discovery (MCP specification,
 * "Authorization"): the unauthenticated probe of the MCP server, the
 * protected-resource metadata (RFC 9728), the authorization-server metadata
 * (RFC 8414, OpenID Connect discovery as fallback) and the dynamic client
 * registration (RFC 7591).
 *
 * <p><b>SSRF fence.</b> The MCP server URL was entered by an admin and may be
 * internal, like any server tool. Every other URL comes out of a response —
 * a foreign server chose it. Such a URL is fetched only when it is on the
 * origin of the MCP server, or is HTTPS and resolves to public addresses
 * only ({@link ClientMetadataDocumentResolver#isPublic}). Every exchange has
 * one deadline for headers and body, a size cap while streaming, and follows
 * no redirect.
 */
@Component
@Slf4j
public class McpAuthDiscoveryClient {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_BYTES = 64 * 1024;
    static final String MCP_PROTOCOL_VERSION = "2025-06-18";

    private static final Pattern AUTH_PARAM =
            Pattern.compile("([A-Za-z0-9_-]+)\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\\s,]+))");

    private final PackHttpClient httpClient;
    private final ObjectMapper json = JsonMapper.builder().build();

    public McpAuthDiscoveryClient() {
        this(new PackHttpClient(TIMEOUT));
    }

    McpAuthDiscoveryClient(PackHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // ──────────────────── Results ────────────────────

    /**
     * Outcome of the unauthenticated probe.
     *
     * @param authRequired        the server answered 401
     * @param resourceMetadataUrl {@code resource_metadata} from {@code WWW-Authenticate}, if any
     * @param scopes              {@code scope} from the challenge, if any
     */
    public record Challenge(boolean authRequired, @Nullable String resourceMetadataUrl, List<String> scopes) {}

    /** RFC 9728 protected-resource metadata, the fields used here. */
    public record ResourceMetadata(String resource, List<String> authorizationServers, List<String> scopesSupported) {}

    /** RFC 8414 authorization-server metadata, the fields used here. */
    public record AuthServerMetadata(
            String issuer,
            String authorizationEndpoint,
            String tokenEndpoint,
            @Nullable String registrationEndpoint,
            boolean clientIdMetadataDocumentSupported,
            List<String> codeChallengeMethods,
            List<String> scopesSupported) {}

    /** RFC 7591 registration response, the fields used here. */
    public record ClientRegistration(
            String clientId, @Nullable String clientSecret) {}

    // ──────────────────── Probe ────────────────────

    /**
     * Sends an MCP {@code initialize} without credentials. A 2xx means the
     * server needs no login; a 401 carries the challenge.
     *
     * @throws McpOAuthException when the server answers anything else or is unreachable
     */
    public Challenge probe(String mcpUrl, PackHttpClient.TlsConfig tls) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"" + MCP_PROTOCOL_VERSION + "\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"vancetope\",\"version\":\"1\"}}}";
        HttpRequest request = HttpRequest.newBuilder(parse(mcpUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = exchange(request, tls, 0, mcpUrl);
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return new Challenge(false, null, List.of());
        }
        if (status != 401) {
            throw new McpOAuthException("MCP server answered HTTP " + status + " to an unauthenticated initialize — "
                    + "expected 401 with an OAuth challenge");
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String header : response.headers().allValues("WWW-Authenticate")) {
            params.putAll(parseChallenge(header));
        }
        return new Challenge(true, params.get("resource_metadata"), splitScopes(params.get("scope")));
    }

    /** Parameters of a {@code Bearer} challenge ({@code key="value"} / {@code key=token}). */
    static Map<String, String> parseChallenge(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        String value = header.trim();
        if (!value.regionMatches(true, 0, "Bearer", 0, 6)) {
            return out;
        }
        Matcher m = AUTH_PARAM.matcher(value.substring(6));
        while (m.find()) {
            String v = m.group(2) != null ? m.group(2).replaceAll("\\\\(.)", "$1") : m.group(3);
            out.put(m.group(1).toLowerCase(Locale.ROOT), v);
        }
        return out;
    }

    // ──────────────────── Metadata ────────────────────

    /**
     * The protected-resource metadata of the MCP server — from the URL the
     * challenge named, else from the well-known locations (path-inserted
     * first, then the origin root).
     */
    public ResourceMetadata resourceMetadata(
            String mcpUrl, @Nullable String advertisedUrl, PackHttpClient.TlsConfig tls) {
        List<String> candidates = new ArrayList<>();
        if (advertisedUrl != null) {
            candidates.add(advertisedUrl);
        } else {
            URI uri = parse(mcpUrl);
            String path = trimTrailingSlash(uri.getRawPath());
            if (!path.isEmpty()) {
                candidates.add(origin(uri) + "/.well-known/oauth-protected-resource" + path);
            }
            candidates.add(origin(uri) + "/.well-known/oauth-protected-resource");
        }
        JsonNode doc = firstDocument(mcpUrl, candidates, tls, "protected-resource metadata");
        String resource = text(doc, "resource");
        List<String> servers = strings(doc, "authorization_servers");
        if (servers.isEmpty()) {
            throw new McpOAuthException("protected-resource metadata names no authorization server");
        }
        return new ResourceMetadata(resource == null ? mcpUrl : resource, servers, strings(doc, "scopes_supported"));
    }

    /** The metadata of the authorization server {@code issuer}. */
    public AuthServerMetadata authServerMetadata(String mcpUrl, String issuer, PackHttpClient.TlsConfig tls) {
        URI uri = parse(issuer);
        String path = trimTrailingSlash(uri.getRawPath());
        List<String> candidates = new ArrayList<>();
        if (path.isEmpty()) {
            candidates.add(origin(uri) + "/.well-known/oauth-authorization-server");
            candidates.add(origin(uri) + "/.well-known/openid-configuration");
        } else {
            candidates.add(origin(uri) + "/.well-known/oauth-authorization-server" + path);
            candidates.add(origin(uri) + "/.well-known/openid-configuration" + path);
            candidates.add(origin(uri) + path + "/.well-known/openid-configuration");
        }
        JsonNode doc = firstDocument(mcpUrl, candidates, tls, "authorization-server metadata");
        String authorize = text(doc, "authorization_endpoint");
        String token = text(doc, "token_endpoint");
        if (authorize == null || token == null) {
            throw new McpOAuthException("authorization-server metadata lacks authorization_endpoint or token_endpoint");
        }
        checkFetchable(mcpUrl, authorize);
        checkFetchable(mcpUrl, token);
        String registration = text(doc, "registration_endpoint");
        JsonNode cimd = doc.get("client_id_metadata_document_supported");
        return new AuthServerMetadata(
                text(doc, "issuer") == null ? issuer : text(doc, "issuer"),
                authorize,
                token,
                registration,
                cimd != null && cimd.isBoolean() && cimd.asBoolean(),
                strings(doc, "code_challenge_methods_supported"),
                strings(doc, "scopes_supported"));
    }

    // ──────────────────── Registration ────────────────────

    /** Registers a public client (PKCE, no secret) at {@code registrationEndpoint}. */
    public ClientRegistration register(
            String mcpUrl,
            String registrationEndpoint,
            String clientName,
            List<String> redirectUris,
            List<String> scopes,
            PackHttpClient.TlsConfig tls) {
        checkFetchable(mcpUrl, registrationEndpoint);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_name", clientName);
        body.put("redirect_uris", redirectUris);
        body.put("grant_types", List.of("authorization_code", "refresh_token"));
        body.put("response_types", List.of("code"));
        body.put("token_endpoint_auth_method", "none");
        if (!scopes.isEmpty()) {
            body.put("scope", String.join(" ", scopes));
        }
        HttpRequest request = HttpRequest.newBuilder(parse(registrationEndpoint))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = exchange(request, tls, MAX_BYTES, registrationEndpoint);
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new McpOAuthException(
                    "client registration at the authorization server failed with HTTP " + response.statusCode());
        }
        JsonNode doc = readJson(response, "client registration response");
        String clientId = text(doc, "client_id");
        if (clientId == null) {
            throw new McpOAuthException("client registration response carries no client_id");
        }
        return new ClientRegistration(clientId, text(doc, "client_secret"));
    }

    // ──────────────────── HTTP ────────────────────

    private JsonNode firstDocument(String mcpUrl, List<String> candidates, PackHttpClient.TlsConfig tls, String what) {
        for (String candidate : candidates) {
            checkFetchable(mcpUrl, candidate);
            HttpRequest request = HttpRequest.newBuilder(parse(candidate))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = exchange(request, tls, MAX_BYTES, candidate);
            if (response.statusCode() == 200) {
                return readJson(response, what);
            }
            log.trace("MCP discovery: {} not at {} (HTTP {})", what, candidate, response.statusCode());
        }
        throw new McpOAuthException("MCP server publishes no " + what);
    }

    /**
     * Whether a URL learned from a response may be fetched: same origin as
     * the configured MCP server, or HTTPS on public addresses only.
     */
    void checkFetchable(String mcpUrl, String learnedUrl) {
        URI learned = parse(learnedUrl);
        if (origin(learned).equalsIgnoreCase(origin(parse(mcpUrl)))) {
            return;
        }
        if (!"https".equalsIgnoreCase(learned.getScheme()) || learned.getRawUserInfo() != null) {
            throw new McpOAuthException("refusing to contact '" + learned.getHost()
                    + "' — URLs outside the MCP server's origin must be https");
        }
        InetAddress[] addresses;
        try {
            addresses = resolveHost(learned.getHost());
        } catch (UnknownHostException e) {
            throw new McpOAuthException("host '" + learned.getHost() + "' does not resolve", e);
        }
        for (InetAddress address : addresses) {
            if (!ClientMetadataDocumentResolver.isPublic(address)) {
                throw new McpOAuthException("refusing to contact '" + learned.getHost()
                        + "' — it is outside the MCP server's origin and not a public address");
            }
        }
    }

    /** Test seam for DNS. */
    InetAddress[] resolveHost(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    /**
     * One request under one deadline for headers and body; the body is
     * collected up to {@code maxBytes} (0 = not read).
     */
    private HttpResponse<byte[]> exchange(
            HttpRequest request, PackHttpClient.TlsConfig tls, int maxBytes, String target) {
        CompletableFuture<HttpResponse<byte[]>> pending = httpClient
                .client(tls)
                .sendAsync(request, info -> new ClientMetadataDocumentResolver.LimitedBodySubscriber(maxBytes));
        try {
            return pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new McpOAuthException("no answer from " + hostOf(target) + " within " + TIMEOUT.toSeconds() + "s", e);
        } catch (ExecutionException e) {
            pending.cancel(true);
            throw new McpOAuthException("request to " + hostOf(target) + " failed: " + rootMessage(e), e);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpOAuthException("interrupted while contacting " + hostOf(target), e);
        }
    }

    private JsonNode readJson(HttpResponse<byte[]> response, String what) {
        JsonNode doc;
        try {
            doc = json.readTree(new String(response.body(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new McpOAuthException(what + " is not valid JSON", e);
        }
        if (doc == null || !doc.isObject()) {
            throw new McpOAuthException(what + " is not a JSON object");
        }
        return doc;
    }

    private static @Nullable String text(JsonNode doc, String field) {
        JsonNode v = doc.get(field);
        if (v == null || !v.isString()) return null;
        String s = v.asString();
        return s.isBlank() ? null : s;
    }

    private static List<String> strings(JsonNode doc, String field) {
        List<String> out = new ArrayList<>();
        JsonNode v = doc.get(field);
        if (v != null && v.isArray()) {
            for (JsonNode item : v) {
                if (item.isString() && !item.asString().isBlank()) out.add(item.asString());
            }
        }
        return out;
    }

    private static List<String> splitScopes(@Nullable String scope) {
        List<String> out = new ArrayList<>();
        if (scope == null) return out;
        for (String part : scope.trim().split("\\s+")) {
            if (!part.isBlank()) out.add(part);
        }
        return out;
    }

    static URI parse(String url) {
        try {
            URI uri = new URI(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new McpOAuthException("'" + url + "' is not an absolute URL");
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw new McpOAuthException("'" + url + "' is not an http(s) URL");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new McpOAuthException("'" + url + "' is not a valid URL", e);
        }
    }

    static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort =
                port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    private static String trimTrailingSlash(@Nullable String path) {
        if (path == null) return "";
        String p = path;
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? url : host;
        } catch (URISyntaxException e) {
            return url;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
