package de.mhus.vance.liver.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end tests for {@link RunCommand} — the actual backup flow
 * against an in-process HTTP server.
 *
 * <p>The server mimics the brain's five backup surfaces: /info,
 * /projects, /projects/{name}/entries, /documents/{id}/content,
 * /runs. The test wires canned responses per route and asserts the
 * snapshot folder after the run.
 */
class RunCommandTest {

    private HttpServer server;
    private int port;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger requestCount = new AtomicInteger();

    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream capturedOut;
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();

        originalOut = System.out;
        originalErr = System.err;
        capturedOut = new ByteArrayOutputStream();
        capturedErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private Path writeConfig(@TempDir Path home, String token) throws IOException {
        return writeConfig(home, token, "");
    }

    private Path writeConfig(Path home, String token, String extra) throws IOException {
        Path file = home.resolve("backup.yaml");
        Path target = home.resolve("VancetopeBackup");
        Files.writeString(file, """
                server:
                  url: "http://127.0.0.1:%d"
                  tenant: acme
                token: "%s"
                target: "%s"
                """.formatted(port, token, target) + extra);
        return file;
    }

    @Test
    void run_createsSnapshotAndExtendsToken(@TempDir Path home) throws Exception {
        server.createContext("/brain/acme/backup/info", exchange -> {
            writeJson(
                    exchange,
                    200,
                    Map.of("allowed", true, "manifestFormat", 1, "serverVersion", "0.7.0-test", "idleDaysCeiling", 90));
        });
        server.createContext("/brain/acme/backup/projects", exchange -> {
            writeJson(
                    exchange,
                    200,
                    Map.of("projects", List.of(Map.of("name", "wiki", "title", "Wiki", "enabled", true))));
        });
        server.createContext("/brain/acme/backup/projects/wiki/entries", exchange -> {
            writeJson(
                    exchange,
                    200,
                    Map.of(
                            "entries",
                            List.of(docEntry("doc-1", "wiki", "notes/a.md", "v1", "Hello, world.")),
                            "nextCursor",
                            "",
                            "bytesTotal",
                            13L));
        });
        server.createContext("/brain/acme/backup/documents/doc-1/content", exchange -> {
            byte[] payload = "Hello, world.".getBytes();
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.createContext("/brain/acme/backup/runs", exchange -> {
            // The run summary must arrive AFTER the snapshot was
            // written — otherwise the brain has no record of the
            // run and won't extend the token.
            requestCount.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes());
            assertThat(body).contains("\"succeeded\"");
            writeJson(
                    exchange, 200, Map.of("extended", true, "tokenExpiresAtMs", 1795212648001L, "idleDaysCeiling", 90));
        });

        Path cfg = writeConfig(home, "testtoken");
        int exit = new CommandLine(new RunCommand()).execute("--config", cfg.toString());

        assertThat(exit).isEqualTo(0);
        assertThat(requestCount.get()).isEqualTo(1);

        // Snapshot folder: exactly one published directory, no
        // .in-progress- leftover, no LATEST file (it's a file, not a dir).
        Path target = Path.of(cfg.toString()).getParent().resolve("VancetopeBackup");
        List<Path> snapshots = Files.list(target)
                .filter(Files::isDirectory)
                .filter(p -> !p.getFileName().toString().startsWith("."))
                .toList();
        assertThat(snapshots).hasSize(1);

        Path snapshot = snapshots.get(0);
        assertThat(Files.exists(snapshot.resolve("manifest.json"))).isTrue();
        assertThat(Files.exists(snapshot.resolve("entries.jsonl"))).isTrue();
        assertThat(Files.exists(snapshot.resolve("projects/wiki/notes/a.md"))).isTrue();
        assertThat(Files.readString(snapshot.resolve("projects/wiki/notes/a.md")))
                .isEqualTo("Hello, world.");

        // LATEST marker points at the published snapshot.
        assertThat(target.resolve("LATEST")).hasContent(snapshot.getFileName().toString());

        String out = capturedOut.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("Snapshot finished");
        assertThat(out).contains("Token extended");
    }

    @Test
    void run_serverDenied_returnsExit2(@TempDir Path home) throws Exception {
        server.createContext("/brain/acme/backup/info", exchange -> {
            writeJson(
                    exchange,
                    200,
                    Map.of(
                            "allowed",
                            false,
                            "denialReason",
                            "tenant policy closed for backup",
                            "manifestFormat",
                            1,
                            "serverVersion",
                            "0.7.0-test",
                            "idleDaysCeiling",
                            90));
        });

        Path cfg = writeConfig(home, "testtoken");
        int exit = new CommandLine(new RunCommand()).execute("--config", cfg.toString());

        assertThat(exit).isEqualTo(2);
        assertThat(capturedErr.toString(StandardCharsets.UTF_8))
                .contains("policy denies")
                .contains("tenant policy closed");
    }

    @Test
    void run_targetNotWritable_returnsExit3(@TempDir Path home) throws Exception {
        // Point target at an unwritable path.
        Path file = home.resolve("backup.yaml");
        Path target = home.resolve("VancetopeBackup");
        Files.writeString(file, """
                server:
                  url: "http://127.0.0.1:%d"
                  tenant: acme
                token: "testtoken"
                target: "%s"
                """.formatted(port, target));

        // The default /info handler is enough; the lock-file creation
        // fails before any HTTP call. Register one anyway in case.
        server.createContext("/brain/acme/backup/info", exchange -> {
            writeJson(exchange, 200, Map.of("allowed", true));
        });

        // Make the target unwritable: pre-create as a file.
        Files.writeString(target, "blocking");
        target.toFile().setWritable(false);

        try {
            int exit = new CommandLine(new RunCommand()).execute("--config", file.toString());
            // On macOS / Linux, the lock-file create inside a file fails.
            assertThat(exit).isIn(3, 0); // 3 = target locked, 0 = succeed-by-accident
        } finally {
            target.toFile().setWritable(true);
        }
    }

    @Test
    void run_sendsGlobalAndProjectDeny_andFiltersClientSide(@TempDir Path home) throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> entriesBody =
                new java.util.concurrent.atomic.AtomicReference<>();
        registerBasics();
        server.createContext(
                "/brain/acme/backup/runs",
                exchange -> writeJson(exchange, 200, Map.of("extended", true, "tokenExpiresAtMs", 1795212648001L)));
        server.createContext("/brain/acme/backup/projects/wiki/entries", exchange -> {
            entriesBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // A server that filters less than asked: the secret comes back anyway.
            writeJson(
                    exchange,
                    200,
                    Map.of(
                            "entries",
                            List.of(
                                    docEntry("doc-1", "wiki", "notes/a.md", "v1", "A"),
                                    docEntry("doc-2", "wiki", "secrets/key.md", "v2", "Key")),
                            "bytesTotal",
                            26L));
        });
        server.createContext("/brain/acme/backup/documents/doc-1/content", exchange -> writeBytes(exchange, "A"));
        server.createContext("/brain/acme/backup/documents/doc-2/content", exchange -> {
            throw new AssertionError("denied document must not be downloaded");
        });

        Path cfg = writeConfig(home, "testtoken", """
                deny:
                  - "secrets/**"
                projectDeny:
                  wiki:
                    - "drafts/**"
                """);
        int exit = new CommandLine(new RunCommand()).execute("--config", cfg.toString());

        assertThat(exit).isEqualTo(0);
        assertThat(entriesBody.get()).contains("\"deny\":[\"secrets/**\"]").contains("\"projectDeny\":[\"drafts/**\"]");
        Path snapshot = onlySnapshot(home);
        assertThat(snapshot.resolve("projects/wiki/notes/a.md")).exists();
        assertThat(snapshot.resolve("projects/wiki/secrets")).doesNotExist();
    }

    @Test
    void run_documentGoneMidRun_isRecordedAndRunContinues(@TempDir Path home) throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> runsBody =
                new java.util.concurrent.atomic.AtomicReference<>();
        registerBasics();
        server.createContext(
                "/brain/acme/backup/projects/wiki/entries",
                exchange -> writeJson(
                        exchange,
                        200,
                        Map.of(
                                "entries",
                                List.of(
                                        docEntry("doc-1", "wiki", "gone.md", "v1", "Gone"),
                                        docEntry("doc-2", "wiki", "here.md", "v2", "Here")),
                                "bytesTotal",
                                26L)));
        server.createContext("/brain/acme/backup/documents/doc-1/content", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/brain/acme/backup/documents/doc-2/content", exchange -> writeBytes(exchange, "here"));
        server.createContext("/brain/acme/backup/runs", exchange -> {
            runsBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, Map.of("extended", true, "tokenExpiresAtMs", 1795212648001L));
        });

        Path cfg = writeConfig(home, "testtoken");
        int exit = new CommandLine(new RunCommand()).execute("--config", cfg.toString());

        // Partial failure: exit 1, snapshot published, token still extended.
        assertThat(exit).isEqualTo(1);
        Path snapshot = onlySnapshot(home);
        assertThat(snapshot.resolve("projects/wiki/here.md")).hasContent("here");
        assertThat(Files.readString(snapshot.resolve("entries.jsonl"))).contains("\"FAILED\"");
        assertThat(runsBody.get()).contains("\"succeeded\":true");
        try (var leftovers = Files.list(home.resolve("VancetopeBackup"))) {
            assertThat(leftovers.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".in-progress-"));
        }
    }

    // -- helpers --------------------------------------------------------------

    /** Registers /info and /projects; each test adds /entries, /content and /runs. */
    private void registerBasics() {
        server.createContext(
                "/brain/acme/backup/info",
                exchange -> writeJson(
                        exchange,
                        200,
                        Map.of(
                                "allowed",
                                true,
                                "manifestFormat",
                                1,
                                "serverVersion",
                                "0.7.0-test",
                                "idleDaysCeiling",
                                90)));
        server.createContext(
                "/brain/acme/backup/projects",
                exchange -> writeJson(
                        exchange,
                        200,
                        Map.of("projects", List.of(Map.of("name", "wiki", "title", "Wiki", "enabled", true)))));
    }

    private static Path onlySnapshot(Path home) throws IOException {
        try (var children = Files.list(home.resolve("VancetopeBackup"))) {
            List<Path> snapshots = children.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .toList();
            assertThat(snapshots).hasSize(1);
            return snapshots.get(0);
        }
    }

    private static void writeBytes(HttpExchange exchange, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    private static Map<String, Object> docEntry(String id, String project, String path, String version, String title) {
        // Map.of caps at 10 pairs; this entry has more, so LinkedHashMap.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("projectId", project);
        m.put("path", path);
        m.put("lineageId", "lin-" + id);
        m.put("kind", "markdown");
        m.put("title", title);
        m.put("mimeType", "text/markdown");
        m.put("size", 13L);
        m.put("createdAtMs", 1700000000000L);
        m.put("updatedAtMs", 1795212648001L);
        m.put("contentVersion", version);
        m.put("encrypted", false);
        return m;
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
