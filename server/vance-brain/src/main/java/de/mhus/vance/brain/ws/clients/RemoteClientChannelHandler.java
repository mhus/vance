package de.mhus.vance.brain.ws.clients;

import de.mhus.vance.api.ws.ErrorData;
import de.mhus.vance.api.ws.LiveChannels;
import de.mhus.vance.api.ws.LiveEnvelope;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.RemoteAttachRequest;
import de.mhus.vance.api.ws.RemoteClientAnnounce;
import de.mhus.vance.api.ws.RemoteClientRoster;
import de.mhus.vance.api.ws.RemoteClientState;
import de.mhus.vance.api.ws.RemoteInputRequest;
import de.mhus.vance.api.ws.RemoteInterruptRequest;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-frame demux for the {@code clients} channel.
 *
 * <p>Two roles share the channel and are told apart by frame type, not by a
 * flag: a foot announces, heartbeats and streams; a watcher lists, attaches and
 * submits. The split matters for trust — for anything a <b>foot</b> sends, the
 * client identity is taken from the socket binding established at announce
 * time, never from the payload. Otherwise any connection could rewrite another
 * client's roster row or forge its output by claiming its id.
 *
 * <p>For anything a <b>watcher</b> sends, the target is authorized against that
 * user's own roster ({@link RemoteClientRegistry#owns}). Attaching to a foot is
 * in practice a shell on someone's laptop, so ownership is the whole rule:
 * there is deliberately no admin override here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RemoteClientChannelHandler {

    private final RemoteClientRegistry registry;
    private final RemoteControlRelay relay;
    private final WebSocketSender sender;
    private final ObjectMapper objectMapper;

    public void handle(WebSocketSession wsSession, ConnectionContext ctx, LiveEnvelope live)
            throws IOException {
        if (live.getPayload() == null) {
            sendError(wsSession, 400, "clients-channel frame missing 'payload'");
            return;
        }
        WebSocketEnvelope inner;
        try {
            inner = objectMapper.convertValue(live.getPayload(), WebSocketEnvelope.class);
        } catch (RuntimeException e) {
            sendError(wsSession, 400, "Invalid clients-channel payload: " + e.getMessage());
            return;
        }
        String type = inner.getType();
        if (type == null || type.isBlank()) {
            sendError(wsSession, 400, "clients-channel payload missing 'type'");
            return;
        }
        switch (type) {
            // ── from the CLI client ──
            case MessageType.CLIENT_ANNOUNCE -> onAnnounce(wsSession, ctx, inner);
            case MessageType.CLIENT_HEARTBEAT -> onHeartbeat(wsSession, inner);
            case MessageType.CLIENT_OUTPUT,
                 MessageType.CLIENT_STATE,
                 MessageType.CLIENT_PROMPT -> onClientPush(wsSession, inner, type);
            // ── from a watcher ──
            case MessageType.CLIENT_LIST -> onList(wsSession, ctx);
            case MessageType.CLIENT_ATTACH -> onAttach(wsSession, ctx, inner);
            case MessageType.CLIENT_DETACH -> onDetach(wsSession, ctx, inner);
            case MessageType.CLIENT_INPUT -> onInput(wsSession, ctx, inner);
            case MessageType.CLIENT_INTERRUPT -> onInterrupt(wsSession, ctx, inner);
            default -> sendError(wsSession, 400, "Unknown clients-channel type: '" + type + "'");
        }
    }

    /** Connection teardown: forget a client here, drop watcher attachments there. */
    public void forgetConnection(WebSocketSession wsSession) {
        relay.detachAll(wsSession);
        registry.forget(wsSession);
    }

    // ─── client side ────────────────────────────────────────────────────

    private void onAnnounce(WebSocketSession wsSession, ConnectionContext ctx, WebSocketEnvelope inner)
            throws IOException {
        RemoteClientAnnounce announce = parse(inner, RemoteClientAnnounce.class);
        if (announce == null || isBlank(announce.getClientId())) {
            sendError(wsSession, 400, "client-announce missing 'clientId'");
            return;
        }
        try {
            registry.announce(wsSession, ctx.getTenantId(), ctx.getUserId(), announce);
        } catch (IllegalStateException conflict) {
            log.debug("client-announce rejected: {}", conflict.getMessage());
            sendError(wsSession, 409, conflict.getMessage());
        }
    }

    private void onHeartbeat(WebSocketSession wsSession, WebSocketEnvelope inner) {
        RemoteClientState state = parse(inner, RemoteClientState.class);
        if (state == null) {
            return;
        }
        RemoteClientRegistry.LocalClient client = registry.heartbeat(wsSession, state);
        if (client == null) {
            log.debug("client-heartbeat from an unannounced connection — ignored");
            return;
        }
        // A heartbeat doubles as a state push: a watcher that is already
        // attached should see busy/uiMode change without a second frame type.
        relay.toWatchers(client.tenantId(), client.userId(), client.clientId(),
                WebSocketEnvelope.notification(MessageType.CLIENT_STATE, state));
    }

    private void onClientPush(WebSocketSession wsSession, WebSocketEnvelope inner, String type) {
        RemoteClientRegistry.LocalClient client = registry.byWsSession(wsSession);
        if (client == null) {
            log.debug("clients-channel '{}' from an unannounced connection — ignored", type);
            return;
        }
        relay.toWatchers(client.tenantId(), client.userId(), client.clientId(), inner);
    }

    // ─── watcher side ───────────────────────────────────────────────────

    private void onList(WebSocketSession wsSession, ConnectionContext ctx) throws IOException {
        RemoteClientRoster roster = RemoteClientRoster.builder()
                .clients(registry.listFor(ctx.getTenantId(), ctx.getUserId()))
                .crossPod(registry.isCrossPod())
                .build();
        sender.sendOnChannel(wsSession, LiveChannels.CLIENTS,
                WebSocketEnvelope.notification(MessageType.CLIENT_ROSTER, roster));
    }

    private void onAttach(WebSocketSession wsSession, ConnectionContext ctx, WebSocketEnvelope inner)
            throws IOException {
        RemoteAttachRequest req = parse(inner, RemoteAttachRequest.class);
        if (req == null || isBlank(req.getClientId())) {
            sendError(wsSession, 400, "client-attach missing 'clientId'");
            return;
        }
        if (!authorize(wsSession, ctx, req.getClientId())) {
            return;
        }
        relay.attachWatcher(wsSession, ctx, req.getClientId());
        relay.toClient(ctx.getTenantId(), ctx.getUserId(), req.getClientId(),
                stamped(req, ctx, MessageType.CLIENT_ATTACH));
    }

    private void onDetach(WebSocketSession wsSession, ConnectionContext ctx, WebSocketEnvelope inner)
            throws IOException {
        RemoteAttachRequest req = parse(inner, RemoteAttachRequest.class);
        if (req == null || isBlank(req.getClientId())) {
            sendError(wsSession, 400, "client-detach missing 'clientId'");
            return;
        }
        // Authorized like every other watcher frame. Without the check, anyone
        // who learned a clientId could detach somebody else's watcher and make
        // that client go quiet — a small denial, but a free one.
        if (!authorize(wsSession, ctx, req.getClientId())) {
            return;
        }
        relay.detachWatcher(wsSession, req.getClientId());
        relay.toClient(ctx.getTenantId(), ctx.getUserId(), req.getClientId(),
                stamped(req, ctx, MessageType.CLIENT_DETACH));
    }

    /**
     * Re-emits an attach/detach frame with the watcher's identity filled in
     * from the connection.
     *
     * <p>Server-set on purpose: the client end counts watchers to decide
     * whether to stream, and it cannot derive who is asking — the frame reaches
     * it relayed, so the socket it arrives on is the brain's. Trusting a
     * sender-supplied value would also let one watcher unsubscribe another.
     */
    private WebSocketEnvelope stamped(RemoteAttachRequest req, ConnectionContext ctx, String type) {
        return WebSocketEnvelope.notification(type, RemoteAttachRequest.builder()
                .clientId(req.getClientId())
                .sinceSeq(req.getSinceSeq())
                .watcherId(ctx.getEditorId())
                .build());
    }

    private void onInput(WebSocketSession wsSession, ConnectionContext ctx, WebSocketEnvelope inner)
            throws IOException {
        RemoteInputRequest req = parse(inner, RemoteInputRequest.class);
        if (req == null || isBlank(req.getClientId()) || req.getLine() == null) {
            sendError(wsSession, 400, "client-input missing 'clientId' or 'line'");
            return;
        }
        if (!authorize(wsSession, ctx, req.getClientId())) {
            return;
        }
        relay.toClient(ctx.getTenantId(), ctx.getUserId(), req.getClientId(), inner);
    }

    private void onInterrupt(WebSocketSession wsSession, ConnectionContext ctx, WebSocketEnvelope inner)
            throws IOException {
        RemoteInterruptRequest req = parse(inner, RemoteInterruptRequest.class);
        if (req == null || isBlank(req.getClientId())) {
            sendError(wsSession, 400, "client-interrupt missing 'clientId'");
            return;
        }
        if (!authorize(wsSession, ctx, req.getClientId())) {
            return;
        }
        relay.toClient(ctx.getTenantId(), ctx.getUserId(), req.getClientId(), inner);
    }

    /**
     * Ownership check. Answered from the requesting user's own roster, so an
     * unowned client is indistinguishable from a non-existent one — which is
     * the right answer to give: the existence of somebody else's laptop is not
     * information this channel owes anyone.
     */
    private boolean authorize(WebSocketSession wsSession, ConnectionContext ctx, String clientId)
            throws IOException {
        if (registry.owns(ctx.getTenantId(), ctx.getUserId(), clientId)) {
            return true;
        }
        log.debug("remote-control denied: user={} has no client '{}'", ctx.getUserId(), clientId);
        sendError(wsSession, 404, "No such client: '" + clientId + "'");
        return false;
    }

    private <T> @Nullable T parse(WebSocketEnvelope inner, Class<T> type) {
        Object data = inner.getData();
        if (data == null) return null;
        try {
            return objectMapper.convertValue(data, type);
        } catch (RuntimeException e) {
            log.debug("clients-channel: cannot decode {}: {}", type.getSimpleName(), e.toString());
            return null;
        }
    }

    private void sendError(WebSocketSession wsSession, int code, String message) throws IOException {
        ErrorData err = ErrorData.builder().errorCode(code).errorMessage(message).build();
        sender.sendOnChannel(wsSession, LiveChannels.CLIENTS,
                WebSocketEnvelope.notification(MessageType.ERROR, err));
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
