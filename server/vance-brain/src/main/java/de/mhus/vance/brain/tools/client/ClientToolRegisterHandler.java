package de.mhus.vance.brain.tools.client;

import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.api.toolhealth.ToolHealthStatus;
import de.mhus.vance.api.tools.ClientToolRegisterRequest;
import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.toolhealth.ToolHealthDocument;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound handler for {@link MessageType#CLIENT_TOOL_REGISTER}. Stores
 * the client's tool list against the bound session so the
 * {@link ClientToolSource} can surface them. Re-registration replaces
 * the previous list.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientToolRegisterHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ClientToolRegistry registry;
    private final SessionService sessionService;
    private final ToolHealthService toolHealthService;

    @Override
    public String type() {
        return MessageType.CLIENT_TOOL_REGISTER;
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ClientToolRegisterRequest request;
        try {
            request = objectMapper.convertValue(
                    envelope.getData(), ClientToolRegisterRequest.class);
        } catch (RuntimeException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid client-tool-register payload: " + e.getMessage());
            return;
        }
        if (request == null || request.getTools() == null) {
            sender.sendError(wsSession, envelope, 400, "tools list is required");
            return;
        }
        // Owner-only registration — see planning/multi-user-sessions.md §2.5.
        // The agent must always route client-tool invocations to the
        // session-owner's WebSocket. A secondary participant in a shared
        // session that hands in its own tool list would otherwise either
        // overwrite the owner's surface or route invocations to a foreign
        // client. Silently accept (success reply) without persisting so
        // the secondary's foot/cortex doesn't crash on an unexpected error.
        if (ctx.getSessionId() == null) {
            sender.sendError(wsSession, envelope, 409, "No session bound");
            return;
        }
        SessionDocument session = sessionService.findBySessionId(ctx.getSessionId()).orElse(null);
        boolean ownerCall = session != null && session.getUserId().equals(ctx.getUserId());
        if (!ownerCall) {
            log.debug("ClientToolRegistry: ignoring non-owner registration on session='{}' "
                            + "user='{}' editor='{}' (owner='{}')",
                    ctx.getSessionId(), ctx.getUserId(), ctx.getEditorId(),
                    session == null ? "?" : session.getUserId());
            sender.sendReply(wsSession, envelope, MessageType.CLIENT_TOOL_REGISTER, null);
            return;
        }
        registry.register(
                ctx.getSessionId(),
                ctx.getEditorId(),
                wsSession,
                List.copyOf(request.getTools()));
        clearDisconnectHealth(ctx.getTenantId(), ctx.getSessionId(), request.getTools());
        sender.sendReply(wsSession, envelope, MessageType.CLIENT_TOOL_REGISTER, null);
    }

    /**
     * Undo the {@code DOWN} the disconnect wrote, for the tools that just came
     * back.
     *
     * <p>The disconnect path marks every client tool of a session unavailable
     * with the note "tool reachable again on next bind" — and nothing kept that
     * promise. Health does not remove a tool from the surface, it **suffixes its
     * description**, so the effect was an agent reading "DOWN — client
     * disconnected" on a tool that was in fact registered and working, and
     * reporting that it had no way to do the thing. Every page reload and every
     * session switch left that behind, permanently, for the whole session.
     *
     * <p>Only entries that are actually non-OK are cleared: {@code markAvailable}
     * appends a history entry, and a reload every minute would otherwise write
     * one per tool per reload for a state that was already fine.
     */
    private void clearDisconnectHealth(
            @Nullable String tenantId, String sessionId, List<ToolSpec> tools) {
        if (tenantId == null) return;
        Set<String> registered = new HashSet<>();
        for (ToolSpec spec : tools) registered.add(spec.getName());
        try {
            // The SESSION scope only, read in one query. Not the cascade: a
            // tenant-level DOWN is somebody's statement about the tool in
            // general, and a client reconnecting is no evidence against it.
            for (ToolHealthDocument h :
                    toolHealthService.listForScope(tenantId, ToolHealthScope.SESSION, sessionId)) {
                if (h.getStatus() == ToolHealthStatus.OK) continue;
                if (!registered.contains(h.getToolName())) continue;
                toolHealthService.markAvailable(tenantId, ToolHealthScope.SESSION, sessionId,
                        h.getToolName(), "Client re-registered the tool", "client-tool-register");
            }
        } catch (RuntimeException e) {
            // A health write must never cost the client its registration — the
            // tools work either way; only their descriptions would lie.
            log.warn("Clearing client-tool health on session '{}' failed: {}",
                    sessionId, e.toString());
        }
    }
}
