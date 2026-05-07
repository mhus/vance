package de.mhus.vance.brain.toolpack.rest;

import de.mhus.vance.brain.toolpack.core.PackHttpClient;
import de.mhus.vance.brain.toolpack.core.SecretResolver;
import de.mhus.vance.brain.tools.ToolInvocationContext;
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
        StringBuilder url = new StringBuilder(baseUrl);
        if (!path.startsWith("/") && !baseUrl.isEmpty()) url.append('/');
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
            return JsonWriter.write(body);
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
                Object parsed = JsonWriter.read(bodyStr);
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

    /**
     * Tiny Jackson-free JSON writer/reader. Vance's existing Jackson
     * lives in vance-brain; the toolpack/core layer aims to stay
     * dependency-light. For the body shapes the LLM produces (objects,
     * arrays, strings, numbers, booleans) hand-rolled is fine.
     */
    static final class JsonWriter {
        static String write(Object value) {
            StringBuilder sb = new StringBuilder();
            writeAny(sb, value);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void writeAny(StringBuilder sb, Object value) {
            if (value == null) { sb.append("null"); return; }
            if (value instanceof Boolean || value instanceof Number) {
                sb.append(value); return;
            }
            if (value instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    writeAny(sb, e.getValue());
                }
                sb.append('}'); return;
            }
            if (value instanceof java.util.Collection<?> c) {
                sb.append('[');
                boolean first = true;
                for (Object item : c) {
                    if (!first) sb.append(',');
                    first = false;
                    writeAny(sb, item);
                }
                sb.append(']'); return;
            }
            writeString(sb, String.valueOf(value));
        }

        private static void writeString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }

        /**
         * Minimal JSON reader — sufficient for parsing typical REST
         * response bodies into nested {@code Map<String,Object>} /
         * {@code List<Object>} / strings / numbers / booleans. Throws
         * {@link IllegalArgumentException} on malformed input.
         */
        static Object read(String json) {
            Cursor c = new Cursor(json);
            c.skipWs();
            Object v = readValue(c);
            c.skipWs();
            if (c.hasMore()) {
                throw new IllegalArgumentException("Trailing input after JSON value at pos " + c.pos());
            }
            return v;
        }

        private static Object readValue(Cursor c) {
            c.skipWs();
            char ch = c.peek();
            if (ch == '"') return readJsonString(c);
            if (ch == '{') return readObject(c);
            if (ch == '[') return readArray(c);
            if (ch == 't' || ch == 'f') return readBool(c);
            if (ch == 'n') { c.expectLiteral("null"); return null; }
            return readNumber(c);
        }

        private static String readJsonString(Cursor c) {
            c.expect('"');
            StringBuilder sb = new StringBuilder();
            while (c.hasMore()) {
                char ch = c.next();
                if (ch == '"') return sb.toString();
                if (ch == '\\' && c.hasMore()) {
                    char esc = c.next();
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            String hex = c.takeChars(4);
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> sb.append(esc);
                    }
                    continue;
                }
                sb.append(ch);
            }
            throw new IllegalArgumentException("Unterminated string at pos " + c.pos());
        }

        private static Map<String, Object> readObject(Cursor c) {
            c.expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            c.skipWs();
            if (c.peek() == '}') { c.next(); return out; }
            while (true) {
                c.skipWs();
                String key = readJsonString(c);
                c.skipWs();
                c.expect(':');
                out.put(key, readValue(c));
                c.skipWs();
                char sep = c.next();
                if (sep == ',') continue;
                if (sep == '}') return out;
                throw new IllegalArgumentException("Expected ',' or '}' at pos " + c.pos());
            }
        }

        private static java.util.List<Object> readArray(Cursor c) {
            c.expect('[');
            java.util.List<Object> out = new java.util.ArrayList<>();
            c.skipWs();
            if (c.peek() == ']') { c.next(); return out; }
            while (true) {
                out.add(readValue(c));
                c.skipWs();
                char sep = c.next();
                if (sep == ',') continue;
                if (sep == ']') return out;
                throw new IllegalArgumentException("Expected ',' or ']' at pos " + c.pos());
            }
        }

        private static Boolean readBool(Cursor c) {
            char ch = c.peek();
            if (ch == 't') { c.expectLiteral("true"); return Boolean.TRUE; }
            c.expectLiteral("false");
            return Boolean.FALSE;
        }

        private static Number readNumber(Cursor c) {
            StringBuilder sb = new StringBuilder();
            while (c.hasMore()) {
                char ch = c.peek();
                if ("-+0123456789eE.".indexOf(ch) < 0) break;
                sb.append(c.next());
            }
            String s = sb.toString();
            if (s.isEmpty()) throw new IllegalArgumentException("Expected number at pos " + c.pos());
            if (s.contains(".") || s.contains("e") || s.contains("E")) {
                return Double.parseDouble(s);
            }
            try { return Long.parseLong(s); }
            catch (NumberFormatException e) { return Double.parseDouble(s); }
        }

        private static final class Cursor {
            final String src; int p;
            Cursor(String src) { this.src = src; }
            char peek() { return src.charAt(p); }
            char next() { return src.charAt(p++); }
            boolean hasMore() { return p < src.length(); }
            int pos() { return p; }
            void skipWs() { while (p < src.length() && Character.isWhitespace(src.charAt(p))) p++; }
            void expect(char c) {
                if (!hasMore() || src.charAt(p) != c)
                    throw new IllegalArgumentException("Expected '" + c + "' at pos " + p);
                p++;
            }
            void expectLiteral(String lit) {
                if (p + lit.length() > src.length() || !src.startsWith(lit, p))
                    throw new IllegalArgumentException("Expected '" + lit + "' at pos " + p);
                p += lit.length();
            }
            String takeChars(int n) {
                if (p + n > src.length())
                    throw new IllegalArgumentException("Expected " + n + " more chars at pos " + p);
                String s = src.substring(p, p + n);
                p += n;
                return s;
            }
        }
    }

    /** Wraps non-recoverable network failures (DNS, TCP, TLS handshake). */
    public static final class RestInvocationException extends RuntimeException {
        public RestInvocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
