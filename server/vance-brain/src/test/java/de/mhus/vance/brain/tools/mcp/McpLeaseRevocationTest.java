package de.mhus.vance.brain.tools.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.brain.tools.rest.SettingsSecretResolver;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import de.mhus.vance.shared.servertool.ServerToolConfig;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.shared.servertool.ServerToolLoader;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.mcp.SessionGuard;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two revocation paths of an MCP lease below the handler: the pool
 * closes a connection only while it still holds the revoked token, and the
 * factory ends a dead holder's remote session with an authenticated DELETE
 * — but only against the endpoint the session was opened on.
 */
class McpLeaseRevocationTest {

    private static final ToolInvocationContext CTX = new ToolInvocationContext("acme", "proj", "s", "proc", "alice");

    private HttpServer server;
    private String endpoint;
    private final AtomicReference<String> deleteSession = new AtomicReference<>();
    private final AtomicReference<String> deleteAuth = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        endpoint = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort() + "/mcp";
        server.createContext("/mcp", ex -> {
            if ("DELETE".equals(ex.getRequestMethod())) {
                deleteSession.set(ex.getRequestHeaders().getFirst("Mcp-Session-Id"));
                deleteAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            }
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Map<String, Object> params() {
        return Map.of(
                "transport",
                "http",
                "url",
                endpoint,
                "auth",
                Map.of("type", "bearer", "token", "t0k"),
                "lease",
                "browser");
    }

    // ──────────────── pool: token match ────────────────

    @Test
    void pool_closesOnlyTheSessionThatHoldsTheRevokedToken() {
        McpLeaseSupport leaseSupport = mock(McpLeaseSupport.class);
        when(leaseSupport.createGuard(any(), any(), any(), any())).thenReturn(new FixedTokenGuard("rl-current"));
        McpConnectionPool pool = new McpConnectionPool(new PackHttpClient(), SecretResolver.PASSTHROUGH, leaseSupport);
        ServerToolDocument doc = ServerToolDocument.builder()
                .id("doc-1")
                .name("browser")
                .type("mcp_server")
                .parameters(params())
                .build();
        pool.acquire(doc, CTX);
        String key = "doc-1|acme|alice";

        assertThat(pool.revokeEntry(key, "rl-stale")).as("stale revocation").isFalse();
        assertThat(pool.entriesSnapshot()).containsKey(key);

        assertThat(pool.revokeEntry(key, "rl-current")).isTrue();
        assertThat(pool.entriesSnapshot()).doesNotContainKey(key);
    }

    // ──────────────── factory: remote termination ────────────────

    private McpToolPackFactory factory(@Nullable Map<String, Object> toolParams) {
        SettingsSecretResolver resolver = mock(SettingsSecretResolver.class);
        when(resolver.resolveForConnector(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        ServerToolLoader loader = mock(ServerToolLoader.class);
        if (toolParams == null) {
            when(loader.load(any(), any(), any())).thenReturn(Optional.empty());
        } else {
            ServerToolConfig cfg = mock(ServerToolConfig.class);
            when(cfg.parameters()).thenReturn(toolParams);
            when(loader.load(eq("acme"), eq("proj"), eq("browser"))).thenReturn(Optional.of(cfg));
        }
        return new McpToolPackFactory(resolver, mock(McpLeaseSupport.class), loader);
    }

    private LeaseHolderInfo deadHolder(String sessionEndpoint) {
        return new LeaseHolderInfo(
                "rl-1",
                McpLeaseHandler.TYPE,
                "browser",
                "doc-1|acme|alice",
                "acme",
                "proj",
                "alice",
                "pod-gone",
                false,
                Map.of("sessionId", "sess-1", "tool", "browser", "endpoint", sessionEndpoint));
    }

    @Test
    void factory_terminatesTheRemoteSession_withTheToolsAuth() {
        factory(params()).terminateRemoteSession(deadHolder(endpoint));

        assertThat(deleteSession.get()).isEqualTo("sess-1");
        assertThat(deleteAuth.get()).isEqualTo("Bearer t0k");
    }

    @Test
    void factory_endpointChangedSinceTheSessionOpened_sendsNothing() {
        factory(params()).terminateRemoteSession(deadHolder("http://old.example/mcp"));

        assertThat(deleteSession.get()).isNull();
    }

    @Test
    void factory_toolGone_sendsNothing() {
        factory(null).terminateRemoteSession(deadHolder(endpoint));

        assertThat(deleteSession.get()).isNull();
    }

    private record FixedTokenGuard(String token) implements SessionGuard {
        @Override
        public String sessionToken() {
            return token;
        }
    }
}
