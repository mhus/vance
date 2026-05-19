package de.mhus.vance.toolpack.rest;

import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.core.PackJson;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Executes a single REST endpoint call. Pure Java —
 * {@link PackHttpClient} for the JDK HttpClient + TLS toggle,
 * {@link SecretResolver} for {@code {{secret:...}}} substitution at
 * invoke time. No Spring, no Vance internals beyond
 * {@link ToolInvocationContext}.
 *
 * <p>The response shape matches what tools commonly return so
 * the LLM can reason about it without an adapter:
 * <pre>
 *   {
 *     "status": 200,
 *     "ok": true,                     // 2xx
 *     "headers": { "content-type": "application/json", … },
 *     "body": "..."                   // raw response body
 *     // for JSON responses, an additional "json" key holds the parsed payload
 *   }
 * </pre>
 *
 * <p>{@link #execute} never throws on non-2xx — it surfaces the
 * status to the LLM so the model can decide whether to retry, ask
 * the user, or treat the error as part of normal flow. Network
 * errors do propagate as {@link RestInvocationException}.
 */
public final class RestHttpInvoker {

    private final PackHttpClient httpClient;
    private final RestApiConfig config;
    private final String baseUrl;
    private final SecretResolver secretResolver;

    public RestHttpInvoker(
            PackHttpClient httpClient,
            RestApiConfig config,
            String baseUrl,
            SecretResolver secretResolver) {
        this.httpClient = Objects.requireNonNull(httpClient);
        this.config = Objects.requireNonNull(config);
        this.baseUrl = baseUrl == null ? "" : stripTrailingSlash(baseUrl);
        this.secretResolver = secretResolver == null ? SecretResolver.NOOP : secretResolver;
    }

    /**
     * Builds and sends one HTTP request for {@code op} with the LLM-
     * supplied {@code params}. Path-/query-/header-parameters and the
     * request body are extracted from {@code params} per the operation
     * schema. Auth header is appended live (secret resolution).
     */
    public Map<String, Object> execute(
            OpenApiOperation op,
            Map<String, Object> params,
            ToolInvocationContext ctx) {
        Map<String, Object> safe = params == null ? Map.of() : params;
        String url = buildUrl(op, safe, ctx);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()));

        // Header params + auth.
        for (String h : op.headerParamNames()) {
            Object v = safe.get(h);
            if (v != null) rb.header(h, String.valueOf(v));
        }
        applyAuthHeader(rb, op, safe, ctx, urlBuilderState(op, safe, ctx));

        // Body & method.
        String method = op.httpMethod();
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
        if (op.bodyParamName() != null && safe.containsKey(op.bodyParamName())) {
            String contentType = op.bodyContentType() == null
                    ? "application/json" : op.bodyContentType();
            String bodyStr = serialiseBody(safe.get(op.bodyParamName()), contentType);
            body = HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8);
            rb.header("Content-Type", contentType);
        }
        rb.method(method, body);

        HttpClient client = httpClient.client(config.tls());
        HttpResponse<String> response;
        try {
            response = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RestInvocationException(
                    "REST call failed (" + method + " " + url + "): " + e.getMessage(), e);
        }
        return toResultMap(response);
    }

    /**
     * Public for unit tests + future MCP-HTTP reuse: produces the
     * fully-resolved request URL (path-template substitution + query
     * string + secret expansion in path/query if any).
     */
    public String buildUrl(
            OpenApiOperation op,
            Map<String, Object> params,
            ToolInvocationContext ctx) {
        UrlBuilderState state = urlBuilderState(op, params, ctx);
        return state.url();
    }

    private UrlBuilderState urlBuilderState(
            OpenApiOperation op,
            Map<String, Object> params,
            ToolInvocationContext ctx) {
        // Path template substitution: replace {var} with URL-encoded value
        String path = op.pathTemplate();
        for (String pp : op.pathParamNames()) {
            Object v = params.get(pp);
            String enc = v == null ? "" : URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8);
            path = path.replace("{" + pp + "}", enc);
        }
        // Query string assembly
        StringBuilder query = new StringBuilder();
        appendQuery(query, op.queryParamNames(), params);
        // API-key auth as query param.
        if (config.auth().type() == AuthSpec.Type.API_KEY
                && config.auth().queryParamName() != null) {
            String resolved = resolveValue(config.auth().value(), ctx);
            appendQueryPair(query, config.auth().queryParamName(), resolved);
        }
        // Resolve secret templates in baseUrl at call time so per-user
        // tenant identifiers (Atlassian cloudId, Slack workspace id, …)
        // can be injected via {{secret:user:...}} and follow the calling
        // user, not the bootstrap user. Empty / unresolved → falls back
        // to the configured static value (which is the typical case for
        // public APIs without per-user templating).
        String effectiveBase = resolveValue(baseUrl, ctx);
        if (effectiveBase == null || effectiveBase.isBlank()) effectiveBase = baseUrl;
        effectiveBase = stripTrailingSlash(effectiveBase);
        StringBuilder url = new StringBuilder(effectiveBase);
        if (!path.startsWith("/") && !effectiveBase.isEmpty()) url.append('/');
        url.append(path);
        if (query.length() > 0) {
            url.append(url.indexOf("?") < 0 ? '?' : '&').append(query);
        }
        return new UrlBuilderState(url.toString());
    }

    private void appendQuery(StringBuilder out, List<String> names, Map<String, Object> params) {
        for (String name : names) {
            Object v = params.get(name);
            if (v == null) continue;
            appendQueryPair(out, name, String.valueOf(v));
        }
    }

    private void appendQueryPair(StringBuilder out, String name, @Nullable String value) {
        if (out.length() > 0) out.append('&');
        out.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
    }

    private void applyAuthHeader(
            HttpRequest.Builder rb,
            OpenApiOperation op,
            Map<String, Object> params,
            ToolInvocationContext ctx,
            UrlBuilderState state) {
        AuthSpec auth = config.auth();
        switch (auth.type()) {
            case NONE -> { /* no auth */ }
            case BEARER -> {
                String token = resolveValue(auth.token(), ctx);
                if (token != null) rb.header("Authorization", PackHttpClient.bearerAuthHeader(token));
            }
            case BASIC -> {
                String user = resolveValue(auth.user(), ctx);
                String pwd = resolveValue(auth.password(), ctx);
                rb.header("Authorization",
                        PackHttpClient.basicAuthHeader(user == null ? "" : user, pwd == null ? "" : pwd));
            }
            case API_KEY -> {
                if (auth.headerName() != null && !auth.headerName().isBlank()) {
                    String value = resolveValue(auth.value(), ctx);
                    if (value != null) rb.header(auth.headerName(), value);
                }
                // queryParamName variant is handled in buildUrl()
            }
        }
    }

    private @Nullable String resolveValue(@Nullable String input, ToolInvocationContext ctx) {
        if (input == null) return null;
        return secretResolver.resolve(input, ctx);
    }

    private static String serialiseBody(@Nullable Object body, String contentType) {
        if (body == null) return "";
        if (body instanceof String s) return s;
        // For JSON content types: produce a minimal JSON string. We
        // rely on Jackson via the dispatcher's already-on-classpath
        // copy; loading lazily so the toolpack package stays Jackson-
        // free at compile time.
        if (contentType.toLowerCase().contains("json")) {
            return PackJson.write(body);
        }
        return String.valueOf(body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toResultMap(HttpResponse<String> response) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", response.statusCode());
        out.put("ok", response.statusCode() >= 200 && response.statusCode() < 300);
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((k, list) -> {
            if (list != null && !list.isEmpty()) headers.put(k, list.get(0));
        });
        out.put("headers", headers);
        String bodyStr = response.body() == null ? "" : response.body();
        out.put("body", bodyStr);
        // Try to parse JSON body for convenience.
        String contentType = headers.getOrDefault("content-type",
                headers.getOrDefault("Content-Type", "")).toLowerCase();
        if (contentType.contains("json") && !bodyStr.isBlank()) {
            try {
                Object parsed = PackJson.read(bodyStr);
                out.put("json", parsed);
            } catch (RuntimeException ignored) { /* leave json key out */ }
        }
        return out;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private record UrlBuilderState(String url) {
    }

    /** Wraps non-recoverable network failures (DNS, TCP, TLS handshake). */
    public static final class RestInvocationException extends RuntimeException {
        public RestInvocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
