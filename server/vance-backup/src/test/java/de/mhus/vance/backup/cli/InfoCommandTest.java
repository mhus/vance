package de.mhus.vance.backup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests for {@link InfoCommand} — the smallest end-to-end command
 * backup ships today. The point of these tests is the wire path:
 * config → API call → pretty output.
 *
 * <p>The loopback HttpServer stands in for the brain; the test
 * asserts the request, returns canned JSON, and captures stdout so
 * the human-readable layout can be checked.
 */
class InfoCommandTest {

    private HttpServer server;
    private int port;
    private final ObjectMapper json = new ObjectMapper();

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
        // Write to the default ~/.vancetope/backup/backup.yaml path
        // so the ConfigLoader finds it without --config override.
        // The InfoCommand reads it from System.getProperty("user.home"),
        // which the Surefire runner sets to the @TempDir value when
        // we tell it to (see Surefire docs for sysprop propagation).
        // For this test we just pass --config explicitly.
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                server:
                  url: "http://127.0.0.1:%d"
                  tenant: acme
                token: "%s"
                """.formatted(port, token));
        return file;
    }

    @Test
    void happyPath_printsHumanReadableInfo(@TempDir Path home) throws Exception {
        server.createContext("/brain/acme/backup/info", exchange -> {
            byte[] body = json.writeValueAsBytes(Map.of(
                    "allowed",
                    true,
                    "serverVersion",
                    "0.7.0-SNAPSHOT",
                    "manifestFormat",
                    1,
                    "idleDaysCeiling",
                    365,
                    "allowedSurfaces",
                    List.of("/backup/info", "/backup/projects")));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        Path cfg = writeConfig(home, "testtoken");
        int exit = new CommandLine(new InfoCommand()).execute("--config", cfg.toString());

        assertThat(exit).isEqualTo(0);
        String out = capturedOut.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("Brain           : http://127.0.0.1:" + port + "/brain/acme/backup");
        assertThat(out).contains("Tenant          : acme");
        assertThat(out).contains("Server version  : 0.7.0-SNAPSHOT");
        assertThat(out).contains("Policy          : allowed");
        assertThat(out).contains("Idle-days ceiling: 365");
        assertThat(out).contains("/backup/info");
    }

    @Test
    void jsonFlag_emitsRawResponse(@TempDir Path home) throws Exception {
        server.createContext("/brain/acme/backup/info", exchange -> {
            byte[] body = json.writeValueAsBytes(Map.of(
                    "allowed",
                    true,
                    "manifestFormat",
                    1,
                    "serverVersion",
                    "0.7.0-SNAPSHOT",
                    "idleDaysCeiling",
                    365,
                    "allowedSurfaces",
                    List.of("/backup/info")));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        Path cfg = writeConfig(home, "testtoken");
        int exit = new CommandLine(new InfoCommand()).execute("--config", cfg.toString(), "--json");

        assertThat(exit).isEqualTo(0);
        String out = capturedOut.toString(StandardCharsets.UTF_8);
        // Pretty-printed JSON: the keys appear in the output.
        assertThat(out).contains("\"serverVersion\"");
        assertThat(out).contains("\"idleDaysCeiling\"");
    }

    @Test
    void serverRejected_returnsExitCode2(@TempDir Path home) throws Exception {
        server.createContext("/brain/acme/backup/info", exchange -> {
            byte[] body = "{\"error\":\"token revoked\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        Path cfg = writeConfig(home, "dead-token");
        int exit = new CommandLine(new InfoCommand()).execute("--config", cfg.toString());

        assertThat(exit).isEqualTo(2);
        String err = capturedErr.toString(StandardCharsets.UTF_8);
        assertThat(err).contains("401");
        assertThat(err).contains("token revoked");
    }

    @Test
    void missingConfig_returnsExitCode4(@TempDir Path home) {
        int exit = new CommandLine(new InfoCommand())
                .execute("--config", home.resolve("does-not-exist.yaml").toString());

        assertThat(exit).isEqualTo(4);
        assertThat(capturedErr.toString(StandardCharsets.UTF_8)).contains("Configuration error");
    }
}
