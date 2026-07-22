package de.mhus.vance.brain.ws.signals;

import de.mhus.vance.api.ws.ErrorData;
import de.mhus.vance.api.ws.LiveEnvelope;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SignalSubscribeRequest;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-frame demux for the {@code signals} channel. Handles the subscription
 * lifecycle (subscribe / unsubscribe / unsubscribe-all) and delegates to
 * {@link SignalBroadcaster}. Signals themselves are server-originated
 * (the client only subscribes to receive them), so there is no inbound
 * {@code signal} type here yet — a future client→server signal (e.g. run-kill)
 * would add a case that routes to its handler.
 *
 * <p>No ACL check on subscribe — same rationale as {@code documents}/
 * {@code pointers}: read access to the path is already gated by the JWT the WS
 * handshake validated.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SignalChannelHandler {

    private static final int MAX_PATHS_PER_WS = 100;

    private final SignalBroadcaster broadcaster;
    private final WebSocketSender sender;
    private final ObjectMapper objectMapper;

    public void handle(WebSocketSession wsSession, ConnectionContext ctx, LiveEnvelope live)
            throws IOException {
        if (live.getPayload() == null) {
            sendError(wsSession, 400, "signals-channel frame missing 'payload'");
            return;
        }
        WebSocketEnvelope inner;
        try {
            inner = objectMapper.convertValue(live.getPayload(), WebSocketEnvelope.class);
        } catch (RuntimeException e) {
            sendError(wsSession, 400, "Invalid signals-channel payload: " + e.getMessage());
            return;
        }
        String type = inner.getType();
        if (type == null || type.isBlank()) {
            sendError(wsSession, 400, "signals-channel payload missing 'type'");
            return;
        }
        switch (type) {
            case MessageType.SIGNAL_SUBSCRIBE -> handleSubscribe(wsSession, ctx, inner);
            case MessageType.SIGNAL_UNSUBSCRIBE -> handleUnsubscribe(wsSession, inner);
            case MessageType.SIGNAL_UNSUBSCRIBE_ALL -> broadcaster.unsubscribeAll(wsSession);
            default -> sendError(wsSession, 400, "Unknown signals-channel type: '" + type + "'");
        }
    }

    private void handleSubscribe(WebSocketSession wsSession, ConnectionContext ctx, WebSocketEnvelope inner)
            throws IOException {
        SignalSubscribeRequest req = parse(inner);
        if (req == null || isBlank(req.getPath())) {
            sendError(wsSession, 400, "signals.subscribe payload missing 'path'");
            return;
        }
        if (!broadcaster.isSubscribed(wsSession, req.getPath())
                && broadcaster.subscriptionCountOf(wsSession) >= MAX_PATHS_PER_WS) {
            sendError(wsSession, 429,
                    "Subscription limit reached (" + MAX_PATHS_PER_WS + " per connection)");
            return;
        }
        broadcaster.subscribe(wsSession, ctx, req.getPath());
    }

    private void handleUnsubscribe(WebSocketSession wsSession, WebSocketEnvelope inner)
            throws IOException {
        SignalSubscribeRequest req = parse(inner);
        if (req == null || isBlank(req.getPath())) {
            sendError(wsSession, 400, "signals.unsubscribe payload missing 'path'");
            return;
        }
        broadcaster.unsubscribe(wsSession, req.getPath());
    }

    private SignalSubscribeRequest parse(WebSocketEnvelope inner) {
        Object data = inner.getData();
        if (data == null) return null;
        try {
            return objectMapper.convertValue(data, SignalSubscribeRequest.class);
        } catch (RuntimeException e) {
            log.debug("signals-channel: cannot decode subscribe payload: {}", e.toString());
            return null;
        }
    }

    private void sendError(WebSocketSession wsSession, int code, String message) throws IOException {
        ErrorData err = ErrorData.builder().errorCode(code).errorMessage(message).build();
        WebSocketEnvelope envelope = WebSocketEnvelope.notification(MessageType.ERROR, err);
        sender.sendOnChannel(wsSession, "signals", envelope);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
