package de.mhus.vance.toolpack.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.McpJsonRpc;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The {@link SessionGuard} contract inside {@link McpConnection}: the
 * guard opens before the transport, every failure after that hands the
 * claim back, a revoked claim closes the session so the next call opens a
 * fresh one, and {@link McpConnection#sessionToken()} follows the guard.
 */
class McpConnectionSessionGuardTest {

    private static final ToolInvocationContext CTX = new ToolInvocationContext("acme", "p", "s", "proc", "alice");

    private static final McpConfig CFG = McpConfig.fromParameters(Map.of("transport", "http", "url", "http://x/mcp"));

    @Test
    void callTool_runsTheGuardInOrder() {
        RecordingGuard guard = new RecordingGuard();
        StubTransport transport = new StubTransport();
        McpConnection conn = new McpConnection(CFG, transport, CTX, SecretResolver.PASSTHROUGH, guard);

        conn.callTool("t", Map.of(), CTX);
        conn.close();

        assertThat(guard.events).containsExactly("open", "sessionId:sess-1", "use", "close");
    }

    @Test
    void transportOpenFails_theClaimIsHandedBack() {
        RecordingGuard guard = new RecordingGuard();
        StubTransport transport = new StubTransport();
        transport.failOpen = true;
        McpConnection conn = new McpConnection(CFG, transport, CTX, SecretResolver.PASSTHROUGH, guard);

        assertThatThrownBy(() -> conn.callTool("t", Map.of(), CTX)).isInstanceOf(IllegalStateException.class);

        assertThat(guard.events).containsExactly("open", "close");
    }

    @Test
    void sessionIdHookFails_theClaimIsHandedBack() {
        RecordingGuard guard = new RecordingGuard();
        guard.failSessionId = true;
        McpConnection conn = new McpConnection(CFG, new StubTransport(), CTX, SecretResolver.PASSTHROUGH, guard);

        assertThatThrownBy(() -> conn.callTool("t", Map.of(), CTX)).isInstanceOf(IllegalStateException.class);

        assertThat(guard.events).containsExactly("open", "sessionId:sess-1", "close");
    }

    @Test
    void revokedClaim_closesTheSession_andTheNextCallOpensAFreshOne() {
        RecordingGuard guard = new RecordingGuard();
        StubTransport transport = new StubTransport();
        McpConnection conn = new McpConnection(CFG, transport, CTX, SecretResolver.PASSTHROUGH, guard);
        conn.callTool("t", Map.of(), CTX);

        guard.revoked = true;
        assertThatThrownBy(() -> conn.callTool("t", Map.of(), CTX)).isInstanceOf(McpSessionRevokedException.class);
        assertThat(transport.open).as("revoked session is closed").isFalse();

        guard.revoked = false;
        conn.callTool("t", Map.of(), CTX);

        assertThat(guard.events)
                .containsExactly("open", "sessionId:sess-1", "use", "use", "close", "open", "sessionId:sess-1", "use");
        assertThat(transport.initializeCount).isEqualTo(2);
    }

    @Test
    void sessionToken_followsTheGuard() {
        RecordingGuard guard = new RecordingGuard();
        McpConnection conn = new McpConnection(CFG, new StubTransport(), CTX, SecretResolver.PASSTHROUGH, guard);
        assertThat(conn.sessionToken()).isNull();

        conn.callTool("t", Map.of(), CTX);
        assertThat(conn.sessionToken()).isEqualTo("token-1");

        conn.close();
        assertThat(conn.sessionToken()).isNull();
        assertThat(new McpConnection(CFG, new StubTransport(), CTX).sessionToken())
                .isNull();
    }

    private static final class RecordingGuard implements SessionGuard {
        final List<String> events = new ArrayList<>();
        boolean revoked;
        boolean failSessionId;
        private int opened;
        private @Nullable String token;

        @Override
        public void onSessionOpen() {
            events.add("open");
            token = "token-" + ++opened;
        }

        @Override
        public void onSessionId(@Nullable String sessionId) {
            events.add("sessionId:" + sessionId);
            if (failSessionId) throw new IllegalStateException("lease lost during payload update");
        }

        @Override
        public void onSessionUse() {
            events.add("use");
            if (revoked) throw new McpSessionRevokedException("revoked", new IllegalStateException("lost"));
        }

        @Override
        public void onSessionClose() {
            events.add("close");
            token = null;
        }

        @Override
        public @Nullable String sessionToken() {
            return token;
        }
    }

    private static final class StubTransport implements McpTransport {
        boolean open;
        boolean failOpen;
        int initializeCount;

        @Override
        public void open() {
            if (failOpen) throw new IllegalStateException("cannot open");
            open = true;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public @Nullable String currentSessionId() {
            return open ? "sess-1" : null;
        }

        @Override
        public Object sendRequest(
                String method, @Nullable Map<String, Object> params, Duration timeout, ToolInvocationContext ctx) {
            if (!open) throw new IllegalStateException("not open");
            if ("initialize".equals(method)) {
                initializeCount++;
                return Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of());
            }
            return Map.of();
        }

        @Override
        public void sendNotification(String method, @Nullable Map<String, Object> params, ToolInvocationContext ctx) {}

        @Override
        public void setNotificationHandler(@Nullable Consumer<McpJsonRpc.Frame.Notification> handler) {}
    }
}
