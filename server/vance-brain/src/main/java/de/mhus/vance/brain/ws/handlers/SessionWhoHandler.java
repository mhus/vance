package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.session.SessionParticipantDto;
import de.mhus.vance.api.session.SessionRosterData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.events.ConnectionEntry;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * On-demand roster lookup for the bound session — replies with the
 * same {@link SessionRosterData} shape that the server-push channel
 * uses, but only to the caller. Drives the {@code /who} slash command
 * in the Web-UI and the equivalent participant lookup in the foot CLI.
 *
 * <p>No authority check beyond {@code canExecute}: any bound
 * participant in the session may see who else is connected — that's
 * the same information the {@code session-roster} server-push frame
 * already broadcasts. See {@code planning/multi-user-sessions.md} §7.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionWhoHandler implements WsHandler {

    private final WebSocketSender sender;
    private final SessionConnectionRegistry connectionRegistry;

    @Override
    public String type() {
        return MessageType.SESSION_WHO;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return ctx.hasSession();
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        String sessionId = ctx.getSessionId();
        List<ConnectionEntry> entries = connectionRegistry.snapshotEntries(sessionId);
        List<SessionParticipantDto> participants = new ArrayList<>(entries.size());
        for (ConnectionEntry entry : entries) {
            participants.add(SessionParticipantDto.builder()
                    .editorId(entry.editorId())
                    .userId(entry.userId())
                    .displayName(entry.displayName())
                    .build());
        }
        SessionRosterData reply = SessionRosterData.builder()
                .sessionId(sessionId)
                .participants(participants)
                .build();
        sender.sendReply(wsSession, envelope, MessageType.SESSION_WHO, reply);
    }
}
