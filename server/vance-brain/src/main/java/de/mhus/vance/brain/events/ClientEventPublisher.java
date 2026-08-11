package de.mhus.vance.brain.events;

import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Pushes server-initiated notifications to <em>every</em> client
 * connected to a given Vance session. Drops events silently if nobody
 * is connected — reliability is not the contract here; it's optimistic
 * rendering (streaming chunks, progress updates, etc.). Authoritative
 * state still flows through persistence + {@code chat-message-appended}.
 *
 * <p>Broadcast semantics: in a multi-user session every participant
 * sees the same progress/notify/stream stream. See
 * {@code planning/multi-user-sessions.md} §3.5.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientEventPublisher {

    private final SessionConnectionRegistry connections;
    private final WebSocketSender sender;

    /**
     * Sends {@code type}/{@code data} to every connection currently
     * bound to {@code sessionId}. Returns {@code true} if at least one
     * frame was written; {@code false} if no connection was registered
     * or every write failed.
     */
    public boolean publish(String sessionId, String type, @Nullable Object data) {
        Collection<WebSocketSession> targets = connections.findAll(sessionId);
        if (targets.isEmpty()) {
            return false;
        }
        int sent = 0;
        for (WebSocketSession ws : targets) {
            try {
                sender.sendNotification(ws, type, data);
                sent++;
            } catch (IOException | RuntimeException e) {
                // RuntimeException matters as much as IOException here: a
                // container write on a closed/half-written endpoint throws
                // IllegalStateException ("session has been closed",
                // "TEXT_PARTIAL_WRITING"), which used to escape this loop
                // into whatever emitted the event. A progress ping raised
                // from inside a tool invocation then surfaced to the engine
                // as "TOOL CALL FAILED", so a client disconnect wrote a
                // bogus tool error into the conversation. This channel is
                // optimistic rendering — a failed frame is never the
                // caller's problem.
                log.debug("Publish to session='{}' type='{}' ws='{}' failed: {}",
                        sessionId, type, ws.getId(), e.toString());
            }
        }
        return sent > 0;
    }
}
