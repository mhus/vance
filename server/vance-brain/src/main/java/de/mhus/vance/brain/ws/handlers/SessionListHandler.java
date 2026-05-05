package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionListRequest;
import de.mhus.vance.api.ws.SessionListResponse;
import de.mhus.vance.api.ws.SessionSummary;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Lists the caller's sessions in the current tenant. Optional
 * {@code projectId} filter narrows the result to a single project. Allowed
 * with or without a bound session.
 */
@Component
@RequiredArgsConstructor
public class SessionListHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final SessionService sessionService;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.SESSION_LIST;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return true;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        String projectId = null;
        if (envelope.getData() != null) {
            try {
                SessionListRequest request =
                        objectMapper.convertValue(envelope.getData(), SessionListRequest.class);
                if (request != null) {
                    projectId = request.getProjectId();
                }
            } catch (IllegalArgumentException e) {
                sender.sendError(wsSession, envelope, 400,
                        "Invalid session.list payload: " + e.getMessage());
                return;
            }
        }

        authority.enforce(ctx, new Resource.Tenant(ctx.getTenantId()), Action.READ);

        List<SessionDocument> documents = isBlank(projectId)
                ? sessionService.listForUser(ctx.getTenantId(), ctx.getUserId())
                : sessionService.listForUserAndProject(ctx.getTenantId(), ctx.getUserId(), projectId);

        List<SessionSummary> summaries = documents.stream().map(SessionListHandler::toSummary).toList();
        SessionListResponse response = SessionListResponse.builder().sessions(summaries).build();
        sender.sendReply(wsSession, envelope, MessageType.SESSION_LIST, response);
    }

    private static SessionSummary toSummary(SessionDocument doc) {
        return SessionSummary.builder()
                .sessionId(doc.getSessionId())
                .projectId(doc.getProjectId())
                .status(doc.getStatus().name())
                .createdAt(toEpochMillis(doc.getCreatedAt()))
                .lastActivityAt(toEpochMillis(doc.getLastActivityAt()))
                .bound(doc.getBoundConnectionId() != null)
                .displayName(doc.getDisplayName())
                .profile(doc.getProfile())
                .firstUserMessage(doc.getFirstUserMessage())
                .lastMessagePreview(doc.getLastMessagePreview())
                .build();
    }

    private static long toEpochMillis(@Nullable Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
