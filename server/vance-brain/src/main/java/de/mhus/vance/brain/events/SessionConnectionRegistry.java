package de.mhus.vance.brain.events;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Remembers which {@link WebSocketSession} currently owns a given
 * Vance session, so brain-side code can push notifications without
 * threading the WebSocket through every call.
 *
 * <p>Populated from the session-bind points (session-create, -resume,
 * -bootstrap) and cleared by the connection-closed hook. One pod
 * serves a given session at a time — no cross-pod routing here.
 *
 * <p>Not every brain action needs a live connection: if nobody is
 * listening, {@link #find} simply returns empty and callers drop the
 * event. This is intentional — server computations continue even
 * without a subscriber.
 */
@Component
@Slf4j
public class SessionConnectionRegistry {

    private final Map<String, WebSocketSession> bySession = new ConcurrentHashMap<>();

    /** Records the session → wsSession binding. Overwrites on reconnect. */
    public void register(String sessionId, WebSocketSession wsSession) {
        bySession.put(sessionId, wsSession);
        log.debug("SessionConnectionRegistry bound session='{}'", sessionId);
    }

    /**
     * Clears the binding for {@code sessionId}. Call on WebSocket close
     * or when the client explicitly unbinds the session.
     */
    public void unregister(String sessionId) {
        WebSocketSession removed = bySession.remove(sessionId);
        if (removed != null) {
            log.debug("SessionConnectionRegistry released session='{}'", sessionId);
        }
    }

    /** Looks up the active connection for a session, if any. */
    public Optional<WebSocketSession> find(String sessionId) {
        if (sessionId == null) return Optional.empty();
        return Optional.ofNullable(bySession.get(sessionId));
    }
}
