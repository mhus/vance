package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.ClientType;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * In-memory registry of active {@link ClientSession}s.
 *
 * Scoped to a single Brain instance for v1. A future clustered Brain would back
 * this with a shared store (MongoDB, Redis); the service boundary stays the same.
 */
@Service
public class ClientSessionRegistry {

    private static final String SESSION_ID_PREFIX = "sess_";

    private final ConcurrentMap<String, ClientSession> sessionsById = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public ClientSessionRegistry() {
        this(Clock.systemUTC());
    }

    public ClientSessionRegistry(Clock clock) {
        this.clock = clock;
    }

    public @Nullable ClientSession get(String sessionId) {
        return sessionsById.get(sessionId);
    }

    public Collection<ClientSession> all() {
        return sessionsById.values();
    }

    /**
     * Returns the existing session for {@code requestedSessionId} if it belongs to
     * {@code userId}, otherwise creates a fresh session. Pass {@code null} for the
     * requested id to always create a new one.
     */
    public Result createOrResume(
            @Nullable String requestedSessionId,
            String userId,
            @Nullable String displayName,
            @Nullable String tenantId,
            ClientType clientType,
            String clientVersion) {
        if (requestedSessionId != null) {
            ClientSession existing = sessionsById.get(requestedSessionId);
            if (existing != null && existing.getUserId().equals(userId)) {
                existing.touch();
                return new Result(existing, true);
            }
            if (existing != null) {
                // Session exists but belongs to another user — do not leak its presence; fail.
                throw new SessionAccessException(
                        "Session " + requestedSessionId + " is not accessible for user " + userId);
            }
            // Unknown session id — treat as not-found so the handshake returns HTTP 404.
            throw new SessionNotFoundException(
                    "Session not found: " + requestedSessionId);
        }
        ClientSession created = new ClientSession(
                newSessionId(),
                userId,
                displayName,
                tenantId,
                clientType,
                clientVersion,
                Instant.now(clock));
        sessionsById.put(created.getSessionId(), created);
        return new Result(created, false);
    }

    public void remove(String sessionId) {
        sessionsById.remove(sessionId);
    }

    private String newSessionId() {
        return SESSION_ID_PREFIX + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public record Result(ClientSession session, boolean resumed) {
    }

    /** Thrown when the caller references a session id that belongs to a different user. */
    public static class SessionAccessException extends RuntimeException {
        public SessionAccessException(String message) {
            super(message);
        }
    }

    /** Thrown when the caller references a session id that does not exist. */
    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String message) {
            super(message);
        }
    }
}
