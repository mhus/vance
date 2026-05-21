package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.ProjectSwitchRequest;
import de.mhus.vance.api.ws.ProjectSwitchResponse;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles {@link MessageType#PROJECT_SWITCH} — moves the spot pointer
 * ({@code ThinkProcessDocument.workingProjectId}) on the bound
 * session's chat process. Direct WS counterpart to the LLM-emitted
 * {@code project_switch} brain-tool, callable without an LLM
 * round-trip so the foot {@code /project &lt;name&gt;} command is
 * snappy.
 *
 * <p>Authorisation: tenant-READ + per-project READ on the target.
 * SYSTEM-kind projects are rejected for the same reason
 * {@code project_switch} rejects them — Eddie's spot is meant for
 * regular user projects.
 *
 * <p>Clear-semantics: {@code name} null / blank / {@code "-"} unsets
 * the spot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectSwitchHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ProjectService projectService;
    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.PROJECT_SWITCH;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return ctx.getSessionId() != null;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession,
                       WebSocketEnvelope envelope) throws IOException {
        ProjectSwitchRequest request;
        try {
            request = envelope.getData() == null
                    ? new ProjectSwitchRequest()
                    : objectMapper.convertValue(envelope.getData(), ProjectSwitchRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid project-switch payload: " + e.getMessage());
            return;
        }
        String requested = normalise(request.getName());

        String tenantId = ctx.getTenantId();
        String sessionId = ctx.getSessionId();
        authority.enforce(ctx, new Resource.Tenant(tenantId), Action.READ);

        Optional<SessionDocument> sessionOpt = sessionService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty() || sessionOpt.get().getChatProcessId() == null) {
            sender.sendError(wsSession, envelope, 404,
                    "Session '" + sessionId + "' has no chat process bound");
            return;
        }
        String chatProcessId = sessionOpt.get().getChatProcessId();

        // Validate target project (when set) before touching the
        // process record — fail fast on hallucinated names so we don't
        // leave a dangling spot pointer.
        if (requested != null) {
            Optional<ProjectDocument> targetOpt =
                    projectService.findByTenantAndName(tenantId, requested);
            if (targetOpt.isEmpty()) {
                sender.sendError(wsSession, envelope, 404,
                        "Project '" + requested + "' not found in tenant '"
                                + tenantId + "'");
                return;
            }
            ProjectDocument target = targetOpt.get();
            if (target.getKind() == ProjectKind.SYSTEM) {
                sender.sendError(wsSession, envelope, 400,
                        "Project '" + requested + "' is SYSTEM — pick a regular user project");
                return;
            }
            authority.enforce(ctx,
                    new Resource.Project(tenantId, target.getName()), Action.READ);
        }

        boolean updated = thinkProcessService.setWorkingProjectId(chatProcessId, requested);
        if (!updated) {
            sender.sendError(wsSession, envelope, 500,
                    "Failed to set working-project on chat-process " + chatProcessId);
            return;
        }
        log.info("PROJECT_SWITCH session='{}' chatProcess='{}' workingProject='{}'",
                sessionId, chatProcessId, requested == null ? "<cleared>" : requested);

        // Echo back the current effective spot — for a successful set
        // that's the requested name; for a clear, null. The client
        // reads the response to render confirmation.
        @Nullable String effective = requested;
        // Re-read so the response reflects what's actually persisted
        // (defensive: a concurrent mutation could have intervened).
        Optional<ThinkProcessDocument> reread = thinkProcessService.findById(chatProcessId);
        if (reread.isPresent()) {
            effective = reread.get().getWorkingProjectId();
        }

        sender.sendReply(wsSession, envelope, MessageType.PROJECT_SWITCH,
                ProjectSwitchResponse.builder().workingProject(effective).build());
    }

    /**
     * Coerces input shapes ({@code null}, blank, {@code "-"},
     * {@code "none"}) to {@code null} so the service helper sees a
     * canonical "clear" signal. Trims whitespace on real values.
     */
    private static @Nullable String normalise(@Nullable String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed) || "none".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }
}
