package de.mhus.vance.brain.hooks;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.HostAccess;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sandboxed HTTP write channel exposed as {@code http.*} to hook
 * scripts. SSRF-hardened: by default loopback, link-local, and
 * private-network destinations are rejected. The tenant setting
 * {@code hooks.http.allowPrivateNetworks} (boolean) flips the rule
 * for self-hosted deployments where the destination is a corporate
 * service behind a VPN.
 *
 * <p>Brain's own publicly-known hostnames are also blocked: a hook
 * must not be able to make the Brain call itself ("/process_create"
 * via an outbound POST would be a process-spawn back-door otherwise).
 * The list comes from the tenant setting {@code vance.brain.publicHosts}
 * (comma-separated hostnames; empty list = no extra block).
 *
 * <p>Methods return a small {@link Map} so the script can read
 * {@code result.status} and {@code result.body} — full HTTP response
 * objects would expose more surface than necessary.
 */
public final class HookHttpClient {

    private static final Logger LOG = LoggerFactory.getLogger("vance.hooks.http");
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final HttpClient httpClient;
    private final Duration timeout;
    private final boolean allowPrivateNetworks;
    private final Set<String> brainPublicHosts;
    private final String hookName;

    public HookHttpClient(
            HttpClient httpClient,
            Duration timeout,
            boolean allowPrivateNetworks,
            Set<String> brainPublicHosts,
            String hookName) {
        this.httpClient = httpClient;
        this.timeout = timeout;
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.brainPublicHosts = brainPublicHosts;
        this.hookName = hookName;
    }

    @HostAccess.Export
    public Map<String, Object> post(String url, @Nullable Object body) {
        return post(url, body, null);
    }

    @HostAccess.Export
    public Map<String, Object> post(
            String url, @Nullable Object body, @Nullable Map<String, Object> options) {
        return send("POST", url, body, options);
    }

    @HostAccess.Export
    public Map<String, Object> put(String url, @Nullable Object body) {
        return put(url, body, null);
    }

    @HostAccess.Export
    public Map<String, Object> put(
            String url, @Nullable Object body, @Nullable Map<String, Object> options) {
        return send("PUT", url, body, options);
    }

    @HostAccess.Export
    public Map<String, Object> get(String url) {
        return get(url, null);
    }

    @HostAccess.Export
    public Map<String, Object> get(String url, @Nullable Map<String, Object> options) {
        return send("GET", url, null, options);
    }

    // ───────────────────────── internals ─────────────────────────

    private Map<String, Object> send(
            String method, String rawUrl,
            @Nullable Object body, @Nullable Map<String, Object> options) {
        URI uri = parseAndValidateUri(rawUrl);

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout);
        applyHeaders(rb, options);

        String contentType = optionString(options, "contentType", "application/json");
        HttpRequest.BodyPublisher publisher = "GET".equals(method)
                ? HttpRequest.BodyPublishers.noBody()
                : buildPublisher(body, contentType);

        if (!"GET".equals(method) && body != null
                && !hasHeader(options, "Content-Type") && !hasHeader(options, "content-type")) {
            rb.header("Content-Type", contentType);
        }
        rb.method(method, publisher);

        HttpResponse<byte[]> resp;
        try {
            LOG.debug("hook '{}' {} {}", hookName, method, uri);
            resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.io.IOException e) {
            throw new HookHttpException(method + " " + uri + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HookHttpException(method + " " + uri + " interrupted", e);
        }

        byte[] payload = resp.body();
        if (payload != null && payload.length > MAX_RESPONSE_BYTES) {
            byte[] trimmed = new byte[MAX_RESPONSE_BYTES];
            System.arraycopy(payload, 0, trimmed, 0, MAX_RESPONSE_BYTES);
            payload = trimmed;
        }
        String text = payload == null
                ? ""
                : new String(payload, java.nio.charset.StandardCharsets.UTF_8);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", resp.statusCode());
        result.put("body", text);
        return result;
    }

    private URI parseAndValidateUri(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new HookHttpException("url must be non-empty");
        }
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new HookHttpException("url is not a valid URI: " + rawUrl, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new HookHttpException(
                    "only http/https URLs are allowed (got '" + scheme + "')");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new HookHttpException("url is missing a host: " + rawUrl);
        }
        // Brain-self block — hostname match, regardless of port.
        String hostLower = host.toLowerCase(java.util.Locale.ROOT);
        for (String b : brainPublicHosts) {
            if (b.isBlank()) continue;
            if (b.equalsIgnoreCase(hostLower)) {
                throw new HookHttpException(
                        "url targets a known Brain host '" + host
                                + "' — hooks must not call Vance back");
            }
        }
        // Loopback / private-net block (unless explicitly allowed).
        InetAddress addr;
        try {
            addr = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new HookHttpException(
                    "url host '" + host + "' did not resolve: " + e.getMessage(), e);
        }
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
            if (!allowPrivateNetworks) {
                throw new HookHttpException(
                        "url targets loopback/link-local '" + host
                                + "' — blocked by hooks SSRF guard");
            }
        } else if (addr.isSiteLocalAddress() || isPrivateIpv4(addr)) {
            if (!allowPrivateNetworks) {
                throw new HookHttpException(
                        "url targets a private network host '" + host
                                + "' — set 'hooks.http.allowPrivateNetworks: true' to allow");
            }
        }
        return uri;
    }

    private static boolean isPrivateIpv4(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        if (bytes.length != 4) return false;
        int o1 = bytes[0] & 0xff;
        int o2 = bytes[1] & 0xff;
        if (o1 == 10) return true;
        if (o1 == 172 && o2 >= 16 && o2 <= 31) return true;
        if (o1 == 192 && o2 == 168) return true;
        if (o1 == 100 && o2 >= 64 && o2 <= 127) return true;       // CGNAT
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void applyHeaders(HttpRequest.Builder rb, @Nullable Map<String, Object> options) {
        if (options == null) return;
        Object headers = options.get("headers");
        if (!(headers instanceof Map<?, ?> m)) return;
        for (Map.Entry<?, ?> entry : m.entrySet()) {
            String k = String.valueOf(entry.getKey());
            Object v = entry.getValue();
            if (v == null) continue;
            try {
                rb.header(k, String.valueOf(v));
            } catch (IllegalArgumentException ex) {
                throw new HookHttpException(
                        "header '" + k + "' rejected by HttpClient: " + ex.getMessage());
            }
        }
    }

    private static boolean hasHeader(@Nullable Map<String, Object> options, String name) {
        if (options == null) return false;
        Object headers = options.get("headers");
        if (!(headers instanceof Map<?, ?> m)) return false;
        for (Object k : m.keySet()) {
            if (name.equalsIgnoreCase(String.valueOf(k))) return true;
        }
        return false;
    }

    private static String optionString(
            @Nullable Map<String, Object> options, String key, String fallback) {
        if (options == null) return fallback;
        Object v = options.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static HttpRequest.BodyPublisher buildPublisher(
            @Nullable Object body, String contentType) {
        if (body == null) return HttpRequest.BodyPublishers.noBody();
        if (body instanceof String s) {
            return HttpRequest.BodyPublishers.ofString(s);
        }
        if (contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            try {
                return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
            } catch (RuntimeException e) {
                throw new HookHttpException(
                        "failed to encode body as JSON: " + e.getMessage(), e);
            }
        }
        return HttpRequest.BodyPublishers.ofString(String.valueOf(body));
    }

    /** Surfaced to JS as a regular {@code Error}. */
    public static final class HookHttpException extends RuntimeException {
        public HookHttpException(String message) { super(message); }
        public HookHttpException(String message, Throwable cause) { super(message, cause); }
    }
}
