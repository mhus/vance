package de.mhus.vance.liver.cli;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link LogoutCommand} — the self-revoke flow.
 *
 * <p>Wire-level: DELETE /brain/{tenant}/integration-tokens/current
 * with Bearer token, expect 204. A 401 from the server is also a
 * success from the operator's perspective (the token is already
 * dead — exactly the state they wanted).
 */
class LogoutCommandTest {

    private HttpServer server;
    private int port;
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

    @Test
    void happyPath_sendsDeleteAndReturnsZero(@TempDir Path home) throws Exception {
        server.createContext("/brain/acme/integration-tokens/current", exchange -> {
            requestCount.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer testtoken");
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
        });

        int exit = runCommand(home, "testtoken");
        assertThat(exit).isEqualTo(0);
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Token revoked");
    }

    @Test
    void alreadyRevoked_returnsZero(@TempDir Path home) throws Exception {
        // Server says 401 — the operator's desired state (logged out)
        // is already achieved. We report success.
        server.createContext("/brain/acme/integration-tokens/current", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.getResponseBody().close();
        });

        int exit = runCommand(home, "deadtoken");
        assertThat(exit).isEqualTo(0);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Already logged out");
    }

    @Test
    void serverError_returnsExit2(@TempDir Path home) throws Exception {
        server.createContext("/brain/acme/integration-tokens/current", exchange -> {
            byte[] body = "{\"error\":\"internal\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        int exit = runCommand(home, "testtoken");
        assertThat(exit).isEqualTo(2);
        assertThat(capturedErr.toString(StandardCharsets.UTF_8)).contains("500");
    }

    @Test
    void missingConfig_returnsExit4(@TempDir Path home) {
        int exit = new CommandLine(new LogoutCommand())
                .execute("--config", home.resolve("does-not-exist.yaml").toString());
        assertThat(exit).isEqualTo(4);
    }

    // -- helpers --------------------------------------------------------------

    private int runCommand(Path home, String token) throws IOException {
        Path config = home.resolve("backup.yaml");
        Files.writeString(config, """
                server:
                  url: "http://127.0.0.1:%d"
                  tenant: acme
                token: "%s"
                target: "%s"
                """.formatted(port, token, home.resolve("snapshots")));
        return new CommandLine(new LogoutCommand()).execute("--config", config.toString());
    }
}
