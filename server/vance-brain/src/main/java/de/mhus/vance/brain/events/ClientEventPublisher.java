package de.mhus.vance.brain.events;

import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Pushes server-initiated notifications to the client bound to a
 * given Vance session. Drops events silently if nobody is connected —
 * reliability is not the contract here; it's optimistic rendering
 * (streaming chunks, progress updates, etc.). Authoritative state
 * still flows through persistence + {@code chat-message-appended}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientEventPublisher {

    private final SessionConnectionRegistry connections;
    private final WebSocketSender sender;

    /**
     * Sends {@code type}/{@code data} to the session's connection.
     * Returns {@code true} if the frame was written (no delivery
     * guarantee beyond that), {@code false} if no connection was
     * registered or the write failed.
     */
    public boolean publish(String sessionId, String type, @Nullable Object data) {
        WebSocketSession ws = connections.find(sessionId).orElse(null);
        if (ws == null) {
            return false;
        }
        try {
            sender.sendNotification(ws, type, data);
            return true;
        } catch (IOException e) {
            log.debug("Publish to session='{}' type='{}' failed: {}",
                    sessionId, type, e.toString());
            return false;
        }
    }
}
