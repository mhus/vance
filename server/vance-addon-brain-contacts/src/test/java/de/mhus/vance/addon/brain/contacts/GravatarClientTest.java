package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GravatarClientTest {

    private static final int LIMIT = 1024;

    private HttpServer server;
    private int status;
    private String contentType;
    private byte[] body;
    private String location;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/avatar/", exchange -> {
            if (contentType != null) exchange.getResponseHeaders().add("Content-Type", contentType);
            if (location != null) exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private GravatarClient client() {
        return new GravatarClient("http://127.0.0.1:" + server.getAddress().getPort() + "/avatar", LIMIT);
    }

    private void answer(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    @Test
    void hashesTrimmedLowerCaseAddress() {
        String expected = "973dfe463ec85785f5f95af5ba3906eedb2d931c24e69824a89ea65dba4e813b";
        assertThat(GravatarClient.hash("test@example.com")).isEqualTo(expected);
        assertThat(GravatarClient.hash("  Test@Example.COM ")).isEqualTo(expected);
    }

    @Test
    void returnsThePicture() {
        answer(200, "image/png", new byte[] {1, 2, 3});
        assertThat(client().fetch("a@b.org", 64)).contains(new byte[] {1, 2, 3});
    }

    @Test
    void notFoundIsNoAvatar() {
        answer(404, "text/plain", new byte[0]);
        assertThat(client().fetch("a@b.org", 64)).isEmpty();
    }

    @Test
    void refusesWhatIsNoPicture() {
        answer(200, "text/html", "<html>".getBytes());
        assertThatThrownBy(() -> client().fetch("a@b.org", 64))
                .isInstanceOf(GravatarUnavailableException.class)
                .hasMessageContaining("text/html");
    }

    @Test
    void refusesAnOversizedAnswer() {
        answer(200, "image/jpeg", new byte[LIMIT + 1]);
        assertThatThrownBy(() -> client().fetch("a@b.org", 64))
                .isInstanceOf(GravatarUnavailableException.class)
                .hasMessageContaining("oversized");
    }

    @Test
    void doesNotFollowRedirects() {
        answer(302, null, new byte[0]);
        location = "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere";
        assertThatThrownBy(() -> client().fetch("a@b.org", 64))
                .isInstanceOf(GravatarUnavailableException.class)
                .hasMessageContaining("302");
    }
}
