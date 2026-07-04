package de.mhus.vance.brain.ws.pointers;

import de.mhus.vance.api.ws.ErrorData;
import de.mhus.vance.api.ws.LiveEnvelope;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.PointerMoveRequest;
import de.mhus.vance.api.ws.PointerSubscribeRequest;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-frame demux for the {@code pointers} channel of the Live-WS
 * multi-channel envelope. Parses the inner {@link WebSocketEnvelope}'s
 * {@code type}, validates, and delegates subscription lifecycle + move
 * fan-out to {@link PointerBroadcaster}.
 *
 * <p>Two guards protect the pod against runaway/hostile clients:
 * <ul>
 *   <li>a per-connection subscription cap ({@value #MAX_PATHS_PER_WS}), and</li>
 *   <li>a per-connection move rate cap ({@value #MAX_MOVES_PER_SEC}/s) —
 *       excess {@code pointer-move} frames are silently <b>dropped</b>, not
 *       error-replied (an error per drop would itself flood the socket).</li>
 * </ul>
 *
 * <p>No ACL check on subscribe — same rationale as the {@code documents}
 * channel: read access to the path is already gated by the JWT the WS
 * handshake validated, and a pointer position leaks nothing a viewer
 * couldn't already infer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PointerChannelHandler {

    private static final int MAX_PATHS_PER_WS = 100;
    private static final int MAX_MOVES_PER_SEC = 60;

    private final PointerBroadcaster broadcaster;
    private final WebSocketSender sender;
    private final ObjectMapper objectMapper;

    /** ws-session-id → fixed-window move-rate state {@code {windowStartMs, count}}. */
    private final Map<String, long[]> rateByWs = new ConcurrentHashMap<>();

    public void handle(WebSocketSession wsSession, ConnectionContext ctx, LiveEnvelope live)
            throws IOException {
        if (live.getPayload() == null) {
            sendError(wsSession, 400, "pointers-channel frame missing 'payload'");
            return;
        }
        WebSocketEnvelope inner;
        try {
            inner = objectMapper.convertValue(live.getPayload(), WebSocketEnvelope.class);
        } catch (RuntimeException e) {
            sendError(wsSession, 400, "Invalid pointers-channel payload: " + e.getMessage());
            return;
        }
        String type = inner.getType();
        if (type == null || type.isBlank()) {
            sendError(wsSession, 400, "pointers-channel payload missing 'type'");
            return;
        }
        switch (type) {
            case MessageType.POINTER_SUBSCRIBE -> handleSubscribe(wsSession, ctx, inner);
            case MessageType.POINTER_UNSUBSCRIBE -> handleUnsubscribe(wsSession, inner);
            case MessageType.POINTER_UNSUBSCRIBE_ALL -> broadcaster.unsubscribeAll(wsSession);
            case MessageType.POINTER_MOVE -> handleMove(wsSession, ctx, inner);
            default -> sendError(wsSession, 400, "Unknown pointers-channel type: '" + type + "'");
        }
    }

    /** Drop all per-connection state — called on WS close. */
    public void forgetConnection(WebSocketSession wsSession) {
        rateByWs.remove(wsSession.getId());
    }

    private void handleSubscribe(WebSocketSession wsSession, ConnectionContext ctx, WebSocketEnvelope inner)
            throws IOException {
        PointerSubscribeRequest req = parseSubscribe(inner);
        if (req == null || isBlank(req.getPath())) {
            sendError(wsSession, 400, "pointers.subscribe payload missing 'path'");
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
        PointerSubscribeRequest req = parseSubscribe(inner);
        if (req == null || isBlank(req.getPath())) {
            sendError(wsSession, 400, "pointers.unsubscribe payload missing 'path'");
            return;
        }
        broadcaster.unsubscribe(wsSession, req.getPath());
    }

    private void handleMove(WebSocketSession wsSession, ConnectionContext ctx, WebSocketEnvelope inner)
            throws IOException {
        PointerMoveRequest req;
        try {
            Object data = inner.getData();
            req = data == null ? null : objectMapper.convertValue(data, PointerMoveRequest.class);
        } catch (RuntimeException e) {
            sendError(wsSession, 400, "Invalid pointers.move payload: " + e.getMessage());
            return;
        }
        if (req == null || isBlank(req.getPath())) {
            sendError(wsSession, 400, "pointers.move payload missing 'path'");
            return;
        }
        // A move is only meaningful for a path this connection subscribed
        // to — that's how it declared intent to participate on that surface.
        if (!broadcaster.isSubscribed(wsSession, req.getPath())) {
            sendError(wsSession, 409, "pointers.move for path without an active subscription");
            return;
        }
        if (isRateLimited(wsSession.getId())) {
            log.trace("pointers.move rate-capped ws='{}' path='{}'", wsSession.getId(), req.getPath());
            return;  // silent drop — see class doc
        }
        broadcaster.move(ctx, req.getPath(), req.getX(), req.getY(), req.getData());
    }

    /** Fixed-window per-connection rate cap; {@code true} means "drop". */
    private boolean isRateLimited(String wsId) {
        long now = System.currentTimeMillis();
        long[] window = rateByWs.computeIfAbsent(wsId, k -> new long[]{now, 0});
        synchronized (window) {
            if (now - window[0] >= 1000L) {
                window[0] = now;
                window[1] = 0;
            }
            window[1]++;
            return window[1] > MAX_MOVES_PER_SEC;
        }
    }

    private PointerSubscribeRequest parseSubscribe(WebSocketEnvelope inner) {
        Object data = inner.getData();
        if (data == null) return null;
        try {
            return objectMapper.convertValue(data, PointerSubscribeRequest.class);
        } catch (RuntimeException e) {
            log.debug("pointers-channel: cannot decode subscribe payload: {}", e.toString());
            return null;
        }
    }

    private void sendError(WebSocketSession wsSession, int code, String message) throws IOException {
        ErrorData err = ErrorData.builder().errorCode(code).errorMessage(message).build();
        WebSocketEnvelope envelope = WebSocketEnvelope.notification(MessageType.ERROR, err);
        sender.sendOnChannel(wsSession, "pointers", envelope);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
