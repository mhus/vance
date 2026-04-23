package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.ClientType;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.WebSocketSession;

/**
 * Server-side representation of an authenticated client session.
 *
 * A session survives individual WebSocket connections — the client may disconnect
 * and resume later via the {@code X-Vance-Session-Id} handshake header. Exactly one
 * {@link WebSocketSession} is bound at a time; a resume request while a connection
 * is still bound is rejected with HTTP 409 at the handshake layer.
 */
@Getter
public class ClientSession {

    private final String sessionId;
    private final String userId;
    private final @Nullable String displayName;
    private final @Nullable String tenantId;
    private final ClientType clientType;
    private final String clientVersion;
    private final Instant createdAt;

    private final AtomicReference<@Nullable WebSocketSession> boundConnection = new AtomicReference<>();

    private volatile Instant lastActivityAt;

    public ClientSession(
            String sessionId,
            String userId,
            @Nullable String displayName,
            @Nullable String tenantId,
            ClientType clientType,
            String clientVersion,
            Instant createdAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.displayName = displayName;
        this.tenantId = tenantId;
        this.clientType = clientType;
        this.clientVersion = clientVersion;
        this.createdAt = createdAt;
        this.lastActivityAt = createdAt;
    }

    public @Nullable WebSocketSession getBoundConnection() {
        return boundConnection.get();
    }

    /**
     * Binds a new WebSocket connection. Returns {@code true} if the bind succeeded,
     * {@code false} if another connection is still bound — callers must then reject
     * the handshake with HTTP 409.
     */
    public boolean bindConnection(WebSocketSession connection) {
        return boundConnection.compareAndSet(null, connection);
    }

    /** Releases the current connection if it matches. No-op otherwise. */
    public void unbindConnection(WebSocketSession connection) {
        boundConnection.compareAndSet(connection, null);
    }

    public void touch() {
        this.lastActivityAt = Instant.now();
    }
}
