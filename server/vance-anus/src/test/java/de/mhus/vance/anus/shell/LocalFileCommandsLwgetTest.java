package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@code lwget}, in particular the try-with-resources lifecycle of the
 * per-download {@link java.net.http.HttpClient}: the response must be fully
 * consumed before the client closes, on both the success and the error path.
 */
class LocalFileCommandsLwgetTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private LocalFileCommands commands;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/ok", exchange -> {
            byte[] body = "hello lwget".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        });
        server.start();
        commands = new LocalFileCommands();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void lwget_success_downloadsToFile() throws Exception {
        Path file = tmp.resolve("out.txt");
        String result = commands.lwget(file.toString(), url("/ok"), false, false, 5);

        assertThat(result).startsWith("Downloaded");
        assertThat(result).contains("HTTP 200");
        assertThat(file).exists();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("hello lwget");
    }

    @Test
    void lwget_httpError_reportsAndCleansUpFile() {
        Path file = tmp.resolve("err.txt");
        String result = commands.lwget(file.toString(), url("/missing"), false, false, 5);

        assertThat(result).startsWith("HTTP 404");
        // BodyHandler.ofFile already wrote the error body — the command must
        // clean it up so no 404 page sits where the user expected the asset.
        assertThat(file).doesNotExist();
    }

    @Test
    void lwget_noClobber_refusesExistingFile() throws Exception {
        Path file = tmp.resolve("exists.txt");
        Files.writeString(file, "keep me");

        String result = commands.lwget(file.toString(), url("/ok"), true, false, 5);

        assertThat(result).startsWith("Refusing");
        assertThat(Files.readString(file)).isEqualTo("keep me");
    }

    @Test
    void lwget_createParents_writesIntoNewDirectory() {
        Path file = tmp.resolve("a/b/out.txt");
        String result = commands.lwget(file.toString(), url("/ok"), false, true, 5);

        assertThat(result).startsWith("Downloaded");
        assertThat(file).exists();
    }
}
