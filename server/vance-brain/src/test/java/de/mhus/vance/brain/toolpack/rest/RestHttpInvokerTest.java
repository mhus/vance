package de.mhus.vance.brain.toolpack.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.brain.toolpack.core.PackHttpClient;
import de.mhus.vance.brain.toolpack.core.SecretResolver;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-style tests for {@link RestHttpInvoker} against a
 * locally-spun JDK {@link HttpServer}. No external network, no
 * Mockito — verifies that:
 *
 * <ul>
 *   <li>Path templates are substituted, query params encoded.</li>
 *   <li>Bearer / Basic / API-key auth headers are set with secret
 *       resolution.</li>
 *   <li>Request body for POST is serialised as JSON.</li>
 *   <li>Response status / headers / body land in the result map,
 *       JSON bodies are parsed under the {@code json} key.</li>
 * </ul>
 */
class RestHttpInvokerTest {

    private HttpServer server;
    private int port;
    private AtomicReference<RecordedRequest> lastRequest;

    @BeforeEach
    void startServer() throws IOException {
        lastRequest = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void getRequest_withPathAndQueryParams() {
        OpenApiOperation op = new OpenApiOperation(
                "getPet", "GET", "/pets/{petId}",
                "Get a pet", null,
                Map.of("type", "object"),
                List.of("petId"), List.of("verbose"), List.of(),
                null, null);
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specUrl", "http://x/spec.json"));

        Map<String, Object> result = newInvoker(cfg).execute(op,
                Map.of("petId", "abc 123", "verbose", "true"), CTX);

        assertThat(result).containsEntry("status", 200).containsEntry("ok", true);
        assertThat(lastRequest.get().method).isEqualTo("GET");
        assertThat(lastRequest.get().uri).isEqualTo("/pets/abc+123?verbose=true");
    }

    @Test
    void postRequest_serialisesJsonBody_andSetsContentType() {
        OpenApiOperation op = new OpenApiOperation(
                "createPet", "POST", "/pets",
                null, null,
                Map.of("type", "object"),
                List.of(), List.of(), List.of(),
                "body", "application/json");
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specUrl", "http://x/spec.json"));

        Map<String, Object> result = newInvoker(cfg).execute(op,
                Map.of("body", new LinkedHashMap<>(Map.of("name", "Rex", "tag", "dog"))),
                CTX);

        assertThat(result).containsEntry("status", 200);
        assertThat(lastRequest.get().method).isEqualTo("POST");
        // JDK HttpServer canonicalises header names (first cap rest lower);
        // case-insensitive lookup keeps the test resilient against either side.
        assertThat(headerValue(lastRequest.get().headers, "Content-Type"))
                .contains("application/json");
        assertThat(lastRequest.get().body).contains("\"name\":\"Rex\"");
    }

    private static String headerValue(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return "";
    }

    @Test
    void bearerAuth_secretIsResolvedAtInvokeTime() {
        OpenApiOperation op = new OpenApiOperation(
                "ping", "GET", "/ping", null, null,
                Map.of("type", "object"),
                List.of(), List.of(), List.of(), null, null);
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specUrl", "http://x/spec.json",
                "auth", Map.of("type", "bearer", "token", "{{secret:api.token}}")));
        SecretResolver resolver = (input, ctx) ->
                input == null ? null : input.replace("{{secret:api.token}}", "abc-xyz");

        new RestHttpInvoker(new PackHttpClient(), cfg, "http://localhost:" + port, resolver)
                .execute(op, Map.of(), CTX);

        assertThat(headerValue(lastRequest.get().headers, "Authorization"))
                .isEqualTo("Bearer abc-xyz");
    }

    @Test
    void basicAuth_buildsHeader() {
        OpenApiOperation op = new OpenApiOperation(
                "ping", "GET", "/ping", null, null,
                Map.of("type", "object"),
                List.of(), List.of(), List.of(), null, null);
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specUrl", "http://x/spec.json",
                "auth", Map.of("type", "basic", "user", "alice", "password", "secret")));

        new RestHttpInvoker(new PackHttpClient(), cfg, "http://localhost:" + port,
                SecretResolver.NOOP)
                .execute(op, Map.of(), CTX);

        // base64("alice:secret") = "YWxpY2U6c2VjcmV0"
        assertThat(headerValue(lastRequest.get().headers, "Authorization"))
                .isEqualTo("Basic YWxpY2U6c2VjcmV0");
    }

    @Test
    void apiKeyAuth_asQueryParam() {
        OpenApiOperation op = new OpenApiOperation(
                "search", "GET", "/search", null, null,
                Map.of("type", "object"),
                List.of(), List.of("q"), List.of(), null, null);
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specUrl", "http://x/spec.json",
                "auth", Map.of("type", "apiKey", "queryParamName", "api_key", "value", "k123")));

        newInvoker(cfg).execute(op, Map.of("q", "vance"), CTX);

        assertThat(lastRequest.get().uri).contains("q=vance").contains("api_key=k123");
    }

    @Test
    void jsonResponse_isExposedAsParsedJson() {
        OpenApiOperation op = new OpenApiOperation(
                "getPet", "GET", "/pets/json", null, null,
                Map.of("type", "object"),
                List.of(), List.of(), List.of(), null, null);
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specUrl", "http://x/spec.json"));

        Map<String, Object> result = newInvoker(cfg).execute(op, Map.of(), CTX);

        assertThat(result).containsKey("json");
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) result.get("json");
        assertThat(json).containsEntry("name", "Rex").containsEntry("id", 42L);
    }

    @Test
    void nonJsonResponse_keepsBodyButNoJsonKey() {
        OpenApiOperation op = new OpenApiOperation(
                "getPlain", "GET", "/plain", null, null,
                Map.of("type", "object"),
                List.of(), List.of(), List.of(), null, null);
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specUrl", "http://x/spec.json"));

        Map<String, Object> result = newInvoker(cfg).execute(op, Map.of(), CTX);

        assertThat(result).containsEntry("body", "plain text response");
        assertThat(result).doesNotContainKey("json");
    }

    @Test
    void httpErrorResponse_passesStatusToCaller_withoutThrowing() {
        OpenApiOperation op = new OpenApiOperation(
                "broken", "GET", "/error/418", null, null,
                Map.of("type", "object"),
                List.of(), List.of(), List.of(), null, null);
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specUrl", "http://x/spec.json"));

        Map<String, Object> result = newInvoker(cfg).execute(op, Map.of(), CTX);

        assertThat(result).containsEntry("status", 418);
        assertThat(result).containsEntry("ok", false);
    }

    private RestHttpInvoker newInvoker(RestApiConfig cfg) {
        return new RestHttpInvoker(
                new PackHttpClient(), cfg,
                "http://localhost:" + port, SecretResolver.NOOP);
    }

    // ─────── Test HTTP server ───────

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().toString();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) headers.put(k, v.get(0));
        });
        lastRequest.set(new RecordedRequest(ex.getRequestMethod(), path, headers, body));

        if (path.startsWith("/error/")) {
            int code = Integer.parseInt(path.substring("/error/".length()));
            ex.sendResponseHeaders(code, 0);
            ex.getResponseBody().close();
            return;
        }
        if (path.startsWith("/pets/json")) {
            byte[] resp = "{\"name\":\"Rex\",\"id\":42}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
            return;
        }
        if (path.startsWith("/plain")) {
            byte[] resp = "plain text response".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
            return;
        }
        // Default: 200 OK, empty body.
        ex.sendResponseHeaders(200, 0);
        ex.getResponseBody().close();
    }

    private record RecordedRequest(String method, String uri, Map<String, String> headers, String body) { }

    private static final ToolInvocationContext CTX = new ToolInvocationContext(
            "tenant", "project", "session", "process", "user");
}
