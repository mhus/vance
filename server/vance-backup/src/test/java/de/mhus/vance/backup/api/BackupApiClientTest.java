package de.mhus.vance.backup.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.api.backup.BackupInfoResponse;
import de.mhus.vance.api.backup.Stored;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests for {@link BackupApiClient}.
 *
 * <p>Backed by a JDK {@code com.sun.net.httpserver.HttpServer} — no
 * mock framework, no extra dependency. The point of the client is
 * wire-format compatibility with the brain, and a real HTTP exchange
 * exercises the JSON parsing path the way {@code mvn package} does.
 *
 * <p>Each test installs a handler that asserts the request, then
 * returns the canned JSON. The path the brain serves
 * ({@code /brain/{tenant}/backup/...}) is built into the client from
 * the brain URL + tenant — we don't reproduce that here.
 */
class BackupApiClientTest {

    private HttpServer server;
    private int port;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private BackupApiClient newClient() {
        // Plain HTTP — the loopback com.sun.net.httpserver.HttpServer
        // does not speak TLS. Production code uses the public
        // constructor with an https:// URL.
        URI brainUrl = URI.create("http://127.0.0.1:" + port);
        return new BackupApiClient(brainUrl, "acme", "testtoken", HttpClient.newHttpClient());
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> new BackupApiClient(URI.create("ftp://example"), "acme", "tok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http:// or https://");
    }

    @Test
    void rejectsMissingHost() {
        assertThatThrownBy(() -> new BackupApiClient(URI.create("http:///path"), "acme", "tok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing a host");
    }

    @Test
    void rejectsPathInBrainUrl() {
        assertThatThrownBy(() -> new BackupApiClient(URI.create("http://example.com/some/path"), "acme", "tok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not include a path");
    }

    @Test
    void apiBase_isResolvedCorrectly() {
        try (var client = newClient()) {
            assertThat(client.apiBase().toString()).isEqualTo("http://127.0.0.1:" + port + "/brain/acme/backup");
            assertThat(client.tenant()).isEqualTo("acme");
        }
    }

    @Test
    void getInfo_parsesServerResponse() throws Exception {
        server.createContext("/brain/acme/backup/info", exchange -> {
            requestCount.incrementAndGet();
            assertThat(authHeader(exchange)).isEqualTo("Bearer testtoken");
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            writeJson(
                    exchange,
                    200,
                    Map.of(
                            "allowed",
                            true,
                            "manifestFormat",
                            1,
                            "serverVersion",
                            "0.7.0-SNAPSHOT",
                            "idleDaysCeiling",
                            365,
                            "allowedSurfaces",
                            List.of("/backup/info", "/backup/projects")));
        });

        try (var client = newClient()) {
            BackupInfoResponse info = client.getInfo();
            assertThat(info.getManifestFormat()).isEqualTo(1);
            assertThat(info.getServerVersion()).isEqualTo("0.7.0-SNAPSHOT");
            assertThat(info.getIdleDaysCeiling()).isEqualTo(365);
        }

        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void listProjects_sendsBodyAsJson() throws Exception {
        server.createContext("/brain/acme/backup/projects", exchange -> {
            requestCount.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            String body = new String(exchange.getRequestBody().readAllBytes());
            assertThat(body).contains("\"include\"");
            writeJson(
                    exchange,
                    200,
                    Map.of("projects", List.of(Map.of("name", "wiki", "title", "Wiki", "enabled", true))));
        });

        try (var client = newClient()) {
            var req = new de.mhus.vance.api.backup.BackupProjectsRequest();
            req.setInclude(List.of("*"));
            req.setExclude(List.of());
            req.setIncludeDisabled(false);
            var resp = client.listProjects(req);
            assertThat(resp.getProjects()).hasSize(1);
            assertThat(resp.getProjects().get(0).getName()).isEqualTo("wiki");
        }
    }

    @Test
    void listEntries_pathEncodesProjectName() throws Exception {
        // HttpServer matches paths after decoding %2F — register with
        // the decoded path; assert the raw (still-encoded) URI in the
        // handler. That's what the brain will see in production
        // (Spring also normalizes but keeps %2F inside the route).
        server.createContext("/brain/acme/backup/projects/wiki/sub/entries", exchange -> {
            requestCount.incrementAndGet();
            assertThat(exchange.getRequestURI().getRawPath()).contains("wiki%2Fsub");
            writeJson(exchange, 200, Map.of("entries", List.of(), "nextCursor", "", "bytesTotal", 0));
        });

        try (var client = newClient()) {
            var req = new de.mhus.vance.api.backup.BackupEntriesRequest();
            req.setDeny(List.of("_vance/**"));
            client.listEntries("wiki/sub", req);
        }
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void downloadContent_sendsIfNoneMatchHeader() throws Exception {
        server.createContext("/brain/acme/backup/documents/doc-123/content", exchange -> {
            requestCount.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("If-None-Match")).isEqualTo("\"v3\"");
            byte[] payload = "hello".getBytes();
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });

        try (var client = newClient()) {
            var resp = client.downloadContent("doc-123", "v3");
            assertThat(resp.body().readAllBytes()).isEqualTo("hello".getBytes());
        }
    }

    @Test
    void reportRun_returnsNewExpiry() throws Exception {
        server.createContext("/brain/acme/backup/runs", exchange -> {
            requestCount.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            String body = new String(exchange.getRequestBody().readAllBytes());
            assertThat(body).contains("\"succeeded\"");
            writeJson(
                    exchange,
                    200,
                    Map.of("extended", true, "tokenExpiresAtMs", 1795212648001L, "idleDaysCeiling", 365));
        });

        try (var client = newClient()) {
            var req = new de.mhus.vance.api.backup.BackupRunsRequest();
            req.setToolVersion("vancetope-backup 0.3.0");
            req.setSucceeded(true);
            req.setDocumentsCount(1234);
            req.setBytesDownloaded(210000);
            req.setBytesTotal(52100000);
            req.setSnapshotId("2026-10-21T09-30-00Z");
            req.setFinishedAtMs(System.currentTimeMillis());
            var resp = client.reportRun(req);
            assertThat(resp.extended()).isTrue();
            assertThat(resp.tokenExpiresAtMs()).isEqualTo(1795212648001L);
        }
    }

    @Test
    void error_401_throwsApiException() throws Exception {
        server.createContext("/brain/acme/backup/info", exchange -> {
            exchange.sendResponseHeaders(401, 0);
            exchange.getResponseBody().close();
        });

        try (var client = newClient()) {
            assertThatThrownBy(client::getInfo).isInstanceOf(ApiException.class).hasMessageContaining("401");
        }
    }

    // -- helpers --------------------------------------------------------------

    private static String authHeader(HttpExchange exchange) {
        return exchange.getRequestHeaders().getFirst("Authorization");
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Compile-time check that Stored is reachable from this test (smoke). */
    @SuppressWarnings("unused")
    private static void smokeStoredEnum() {
        Stored s = Stored.LINKED;
    }
}
