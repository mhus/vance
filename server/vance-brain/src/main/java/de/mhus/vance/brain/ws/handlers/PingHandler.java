package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.PingData;
import de.mhus.vance.api.ws.PongData;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers {@link MessageType#PING} with a {@link MessageType#PONG}. Allowed
 * with or without a bound session — keep-alive is orthogonal to session state.
 */
@Component
@RequiredArgsConstructor
public class PingHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final Clock clock = Clock.systemUTC();

    @Override
    public String type() {
        return MessageType.PING;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return true;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        PingData ping = objectMapper.convertValue(envelope.getData(), PingData.class);
        PongData pong = PongData.builder()
                .clientTimestamp(ping.getClientTimestamp())
                .serverTimestamp(Instant.now(clock).toEpochMilli())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PONG, pong);
    }
}
