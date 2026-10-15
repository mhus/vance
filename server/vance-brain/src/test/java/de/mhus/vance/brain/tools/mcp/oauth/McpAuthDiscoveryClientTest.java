package de.mhus.vance.brain.tools.mcp.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.toolpack.core.PackHttpClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the discovery chain against a local server that plays MCP server and
 * authorization server on one origin.
 */
class McpAuthDiscoveryClientTest {

    private HttpServer server;
    private String base;
    private final AtomicReference<String> registrationBody = new AtomicReference<>();
    private final McpAuthDiscoveryClient client = new McpAuthDiscoveryClient(new PackHttpClient());

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/mcp", ex -> {
            ex.getResponseHeaders()
                    .add(
                            "WWW-Authenticate",
                            "Bearer resource_metadata=\"" + base
                                    + "/.well-known/oauth-protected-resource/mcp\", scope=\"read write\"");
            send(ex, 401, "");
        });
        server.createContext("/open", ex -> send(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        server.createContext(
                "/.well-known/oauth-protected-resource/mcp",
                ex -> send(
                        ex,
                        200,
                        "{\"resource\":\"" + base + "/mcp\",\"authorization_servers\":[\"" + base
                                + "\"],\"scopes_supported\":[\"read\"]}"));
        server.createContext(
                "/.well-known/oauth-authorization-server",
                ex -> send(
                        ex,
                        200,
                        "{\"issuer\":\"" + base
                                + "\",\"authorization_endpoint\":\"" + base + "/authorize\",\"token_endpoint\":\""
                                + base
                                + "/token\",\"registration_endpoint\":\"" + base + "/register\","
                                + "\"code_challenge_methods_supported\":[\"S256\"]}"));
        // Metadata for a different resource — must not be used for /mcp.
        server.createContext(
                "/.well-known/oauth-protected-resource/elsewhere",
                ex -> send(
                        ex,
                        200,
                        "{\"resource\":\"https://other.example/mcp\",\"authorization_servers\":[\"" + base + "\"]}"));
        // An authorization server that speaks for another issuer.
        server.createContext(
                "/.well-known/oauth-authorization-server/tenant-b",
                ex -> send(
                        ex,
                        200,
                        "{\"issuer\":\"" + base + "/tenant-a\",\"authorization_endpoint\":\"" + base
                                + "/authorize\",\"token_endpoint\":\"" + base + "/token\"}"));
        server.createContext("/register", ex -> {
            registrationBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(ex, 201, "{\"client_id\":\"dyn-client-1\"}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void probe_readsChallenge() {
        McpAuthDiscoveryClient.Challenge c = client.probe(base + "/mcp", PackHttpClient.TlsConfig.DEFAULT);

        assertThat(c.authRequired()).isTrue();
        assertThat(c.resourceMetadataUrl()).isEqualTo(base + "/.well-known/oauth-protected-resource/mcp");
        assertThat(c.scopes()).containsExactly("read", "write");
    }

    @Test
    void probe_reportsOpenServer() {
        assertThat(client.probe(base + "/open", PackHttpClient.TlsConfig.DEFAULT)
                        .authRequired())
                .isFalse();
    }

    @Test
    void fullChain_discoversAndRegisters() {
        String mcp = base + "/mcp";
        McpAuthDiscoveryClient.Challenge c = client.probe(mcp, PackHttpClient.TlsConfig.DEFAULT);
        McpAuthDiscoveryClient.ResourceMetadata rm =
                client.resourceMetadata(mcp, c.resourceMetadataUrl(), PackHttpClient.TlsConfig.DEFAULT);
        McpAuthDiscoveryClient.AuthServerMetadata as =
                client.authServerMetadata(mcp, rm.authorizationServers().get(0), PackHttpClient.TlsConfig.DEFAULT);
        McpAuthDiscoveryClient.ClientRegistration reg = client.register(
                mcp,
                as.registrationEndpoint(),
                "Vancetope (acme)",
                List.of("https://v.example/cb"),
                List.of("read"),
                PackHttpClient.TlsConfig.DEFAULT);

        assertThat(rm.resource()).isEqualTo(mcp);
        assertThat(as.authorizationEndpoint()).isEqualTo(base + "/authorize");
        assertThat(as.tokenEndpoint()).isEqualTo(base + "/token");
        assertThat(as.codeChallengeMethods()).contains("S256");
        assertThat(as.clientIdMetadataDocumentSupported()).isFalse();
        assertThat(reg.clientId()).isEqualTo("dyn-client-1");
        assertThat(reg.clientSecret()).isNull();
        assertThat(registrationBody.get())
                .contains("\"token_endpoint_auth_method\":\"none\"")
                .contains("https://v.example/cb");
    }

    @Test
    void resourceMetadata_fallsBackToWellKnownPath() {
        McpAuthDiscoveryClient.ResourceMetadata rm =
                client.resourceMetadata(base + "/mcp", null, PackHttpClient.TlsConfig.DEFAULT);

        assertThat(rm.authorizationServers()).containsExactly(base);
    }

    @Test
    void resourceMetadata_forAnotherResource_isRejected() {
        String mcp = base + "/mcp";
        assertThatThrownBy(() -> client.resourceMetadata(
                        mcp,
                        base + "/.well-known/oauth-protected-resource/elsewhere",
                        PackHttpClient.TlsConfig.DEFAULT))
                .isInstanceOf(McpOAuthException.class)
                .hasMessageContaining("not for the MCP server");
    }

    @Test
    void authServerMetadata_ofAnotherIssuer_isRejected() {
        assertThatThrownBy(() ->
                        client.authServerMetadata(base + "/mcp", base + "/tenant-b", PackHttpClient.TlsConfig.DEFAULT))
                .isInstanceOf(McpOAuthException.class)
                .hasMessageContaining("declares issuer");
    }

    @Test
    void authServerMetadata_carriesIssuerAndIssSupportFlag() {
        McpAuthDiscoveryClient.AuthServerMetadata as =
                client.authServerMetadata(base + "/mcp", base + "/", PackHttpClient.TlsConfig.DEFAULT);

        // trailing slash on the looked-up identifier is tolerated; the
        // declared value is what the callback compares iss against
        assertThat(as.issuer()).isEqualTo(base);
        assertThat(as.issParameterSupported()).isFalse();
    }

    @Test
    void resourceCovers_acceptsSameUrlOrParentOnSameOriginOnly() {
        String mcp = "https://mcp.example.com/v1/mcp";
        assertThat(McpAuthDiscoveryClient.resourceCovers("https://mcp.example.com/v1/mcp", mcp))
                .isTrue();
        assertThat(McpAuthDiscoveryClient.resourceCovers("https://MCP.example.com:443/v1/mcp/", mcp))
                .isTrue();
        assertThat(McpAuthDiscoveryClient.resourceCovers("https://mcp.example.com", mcp))
                .isTrue();
        assertThat(McpAuthDiscoveryClient.resourceCovers("https://mcp.example.com/v1", mcp))
                .isTrue();
        assertThat(McpAuthDiscoveryClient.resourceCovers("https://mcp.example.com/v", mcp))
                .isFalse();
        assertThat(McpAuthDiscoveryClient.resourceCovers("https://mcp.example.com/v1/mcp/sub", mcp))
                .isFalse();
        assertThat(McpAuthDiscoveryClient.resourceCovers("https://evil.example/v1/mcp", mcp))
                .isFalse();
        assertThat(McpAuthDiscoveryClient.resourceCovers("http://mcp.example.com/v1/mcp", mcp))
                .isFalse();
        assertThat(McpAuthDiscoveryClient.resourceCovers("https://mcp.example.com/v1/mcp#x", mcp))
                .isFalse();
    }

    @Test
    void parseChallenge_handlesQuotedAndBareValues() {
        Map<String, String> p = McpAuthDiscoveryClient.parseChallenge(
                "Bearer error=\"invalid_token\", resource_metadata=\"https://x/m\", scope=files");

        assertThat(p).containsEntry("resource_metadata", "https://x/m").containsEntry("scope", "files");
        assertThat(McpAuthDiscoveryClient.parseChallenge("Basic realm=\"x\"")).isEmpty();
    }

    @Test
    void checkFetchable_allowsSameOrigin_evenWhenPrivate() {
        assertThatCode(() -> client.checkFetchable("http://10.1.2.3:8080/mcp", "http://10.1.2.3:8080/.well-known/x"))
                .doesNotThrowAnyException();
    }

    @Test
    void checkFetchable_rejectsForeignHttp() {
        assertThatThrownBy(() -> client.checkFetchable("https://mcp.example/mcp", "http://as.example/meta"))
                .isInstanceOf(McpOAuthException.class)
                .hasMessageContaining("https");
    }

    @Test
    void checkFetchable_rejectsForeignPrivateAddress() {
        McpAuthDiscoveryClient internal = new McpAuthDiscoveryClient(new PackHttpClient()) {
            @Override
            InetAddress[] resolveHost(String host) throws UnknownHostException {
                return new InetAddress[] {InetAddress.getByName("10.42.0.7")};
            }
        };
        assertThatThrownBy(() -> internal.checkFetchable("https://mcp.example/mcp", "https://as.internal/meta"))
                .isInstanceOf(McpOAuthException.class)
                .hasMessageContaining("not a public address");
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }
}
