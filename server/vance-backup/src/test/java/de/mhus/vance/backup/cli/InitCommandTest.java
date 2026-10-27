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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link InitCommand} — the interactive setup.
 *
 * <p>The loopback server mimics the brain's two endpoints that init
 * calls: POST /access (login) and POST /integration-tokens (mint).
 * The test passes every prompt via flags, so it does not need a
 * real terminal — that's what {@code --yes} + all-flags gives us.
 */
class InitCommandTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger loginRequests = new AtomicInteger();
    private final AtomicInteger mintRequests = new AtomicInteger();

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
    void writesConfigAndToken(@TempDir Path home) throws Exception {
        // Mock the access login
        server.createContext("/brain/acme/access/road.runner", exchange -> {
            loginRequests.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            String body = new String(exchange.getRequestBody().readAllBytes());
            assertThat(body).contains("\"username\":\"road.runner\"");
            assertThat(body).contains("\"password\":\"beep-beep\"");
            writeJson(exchange, 200, Map.of("token", "ACCESS_JWT"));
        });

        // Mock the integration-token mint
        server.createContext("/brain/acme/integration-tokens", exchange -> {
            mintRequests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer ACCESS_JWT");
            String body = new String(exchange.getRequestBody().readAllBytes());
            assertThat(body).contains("\"backup\"");
            assertThat(body).contains("\"idleDays\":30");
            writeJson(
                    exchange,
                    200,
                    Map.of(
                            "tokenId",
                            "abc",
                            "token",
                            "BACKUP_JWT",
                            "scopeProfiles",
                            List.of("backup"),
                            "label",
                            "test",
                            "expiresAt",
                            1795212648001L,
                            "createdAt",
                            1792627848001L));
        });

        Path pwFile = home.resolve("password.txt");
        Files.writeString(pwFile, "beep-beep");

        Path config = home.resolve("backup.yaml");
        int exit = new CommandLine(new InitCommand())
                .execute(
                        "--config",
                        config.toString(),
                        "--brain-url",
                        "http://127.0.0.1:" + port,
                        "--tenant",
                        "acme",
                        "--user",
                        "road.runner",
                        "--password-file",
                        pwFile.toString(),
                        "--label",
                        "test",
                        "--idle-days",
                        "30",
                        "--yes");

        assertThat(exit).isEqualTo(0);
        assertThat(loginRequests.get()).isEqualTo(1);
        assertThat(mintRequests.get()).isEqualTo(1);

        // The config file must have all three pieces.
        String written = Files.readString(config);
        assertThat(written).contains("http://127.0.0.1:" + port);
        assertThat(written).contains("acme");
        assertThat(written).contains("BACKUP_JWT");

        // And it must round-trip through the loader.
        var cfg = new de.mhus.vance.backup.config.ConfigLoader().loadFrom(config);
        assertThat(cfg.server.url).contains("127.0.0.1");
        assertThat(cfg.server.tenant).isEqualTo("acme");
        assertThat(cfg.token).isEqualTo("BACKUP_JWT");
    }

    @Test
    void loginFailure_returnsExit2(@TempDir Path home) throws Exception {
        server.createContext("/brain/acme/access/road.runner", exchange -> {
            loginRequests.incrementAndGet();
            writeJson(exchange, 401, Map.of("error", "invalid credentials"));
        });

        Path pwFile = home.resolve("password.txt");
        Files.writeString(pwFile, "wrong-password");

        Path config = home.resolve("backup.yaml");
        int exit = new CommandLine(new InitCommand())
                .execute(
                        "--config",
                        config.toString(),
                        "--brain-url",
                        "http://127.0.0.1:" + port,
                        "--tenant",
                        "acme",
                        "--user",
                        "road.runner",
                        "--password-file",
                        pwFile.toString(),
                        "--yes");

        assertThat(exit).isEqualTo(2);
        assertThat(loginRequests.get()).isEqualTo(1);
        assertThat(mintRequests.get()).isEqualTo(0); // never reached
        // No config written.
        assertThat(Files.exists(config)).isFalse();
        assertThat(capturedErr.toString(StandardCharsets.UTF_8)).contains("Login failed");
    }

    @Test
    void mintFailure_returnsExit2(@TempDir Path home) throws Exception {
        server.createContext("/brain/acme/access/road.runner", exchange -> {
            writeJson(exchange, 200, Map.of("token", "ACCESS_JWT"));
        });
        server.createContext("/brain/acme/integration-tokens", exchange -> {
            writeJson(exchange, 400, Map.of("error", "unknown profile"));
        });

        Path pwFile = home.resolve("password.txt");
        Files.writeString(pwFile, "beep-beep");

        Path config = home.resolve("backup.yaml");
        int exit = new CommandLine(new InitCommand())
                .execute(
                        "--config",
                        config.toString(),
                        "--brain-url",
                        "http://127.0.0.1:" + port,
                        "--tenant",
                        "acme",
                        "--user",
                        "road.runner",
                        "--password-file",
                        pwFile.toString(),
                        "--yes");

        assertThat(exit).isEqualTo(2);
        assertThat(capturedErr.toString(StandardCharsets.UTF_8)).contains("Token mint failed");
    }

    @Test
    void passwordFileMissing_returnsExit4(@TempDir Path home) {
        int exit = new CommandLine(new InitCommand())
                .execute(
                        "--config",
                        home.resolve("backup.yaml").toString(),
                        "--brain-url",
                        "http://127.0.0.1:" + port,
                        "--tenant",
                        "acme",
                        "--user",
                        "road.runner",
                        "--password-file",
                        home.resolve("does-not-exist").toString(),
                        "--yes");
        assertThat(exit).isEqualTo(4);
    }

    // -- helpers --------------------------------------------------------------

    private void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = new tools.jackson.databind.ObjectMapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
