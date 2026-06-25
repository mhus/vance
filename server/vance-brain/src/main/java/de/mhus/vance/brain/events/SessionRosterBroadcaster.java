package de.mhus.vance.brain.events;

import de.mhus.vance.api.session.SessionParticipantDto;
import de.mhus.vance.api.session.SessionRosterData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Pushes a {@code session-roster} frame to every connection of a
 * session whenever its participant roster changes — see
 * {@code planning/multi-user-sessions.md} §7.
 *
 * <p>Triggered by {@link SessionRosterChangedEvent} fired from
 * {@link SessionConnectionRegistry}. Listener is async so a slow
 * write to one connection doesn't stall the register/unregister
 * caller. Errors per-connection are logged at debug; missing
 * connections are not retried (clients will re-fetch the roster on
 * reconnect anyway).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionRosterBroadcaster {

    private final SessionConnectionRegistry connectionRegistry;
    private final WebSocketSender sender;

    @EventListener
    @Async
    public void onRosterChanged(SessionRosterChangedEvent event) {
        String sessionId = event.sessionId();
        Collection<WebSocketSession> targets = connectionRegistry.findAll(sessionId);
        if (targets.isEmpty()) return;

        SessionRosterData payload = SessionRosterData.builder()
                .sessionId(sessionId)
                .participants(snapshotParticipants(sessionId))
                .build();

        for (WebSocketSession ws : targets) {
            try {
                sender.sendNotification(ws, MessageType.SESSION_ROSTER, payload);
            } catch (IOException e) {
                log.debug("Roster push to session='{}' ws='{}' failed: {}",
                        sessionId, ws.getId(), e.toString());
            }
        }
    }

    private List<SessionParticipantDto> snapshotParticipants(String sessionId) {
        List<ConnectionEntry> entries = connectionRegistry.snapshotEntries(sessionId);
        List<SessionParticipantDto> out = new ArrayList<>(entries.size());
        for (ConnectionEntry entry : entries) {
            out.add(SessionParticipantDto.builder()
                    .editorId(entry.editorId())
                    .userId(entry.userId())
                    .displayName(entry.displayName())
                    .build());
        }
        return out;
    }
}
