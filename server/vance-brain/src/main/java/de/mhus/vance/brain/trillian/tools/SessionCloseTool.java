package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Leaves a session the Trillian opened itself (A6/D6). Idle-close would do
 * it eventually anyway; this is the explicit "I am done here".
 */
@Component
@RequiredArgsConstructor
public class SessionCloseTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "sessionId",
                            Map.of(
                                    "type", "string",
                                    "description", "A session you opened with session_open.")),
            "required", List.of("sessionId"));

    private final SessionService sessionService;
    /** Breaks cycles through the session lifecycle service. */
    private final ObjectProvider<SessionLifecycleService> lifecycleProvider;

    @Override
    public String name() {
        return "session_close";
    }

    @Override
    public String description() {
        return "Leave a session you opened: it closes with its processes. " + "Your own sessions only.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("executive");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianUserEngine.ROLE_TRILLIAN_USER);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("sessionId");
        if (!(raw instanceof String sessionId) || sessionId.isBlank()) {
            throw new ToolException("'sessionId' is required");
        }
        Optional<SessionDocument> sessionOpt = sessionService.findBySessionId(sessionId.trim());
        if (sessionOpt.isEmpty()) {
            throw new ToolException("Session '" + sessionId + "' not found");
        }
        SessionDocument session = sessionOpt.get();
        if (!session.getTenantId().equals(ctx.tenantId())) {
            throw new ToolException("Session '" + sessionId + "' is in another tenant");
        }
        if (ctx.userId() == null || !ctx.userId().equals(session.getUserId())) {
            throw new ToolException("Session '" + sessionId + "' is not yours — only sessions "
                    + "this Trillian opened itself can be closed");
        }
        lifecycleProvider.getObject().closeWithCascade(session.getSessionId());
        return Map.of("sessionId", session.getSessionId(), "status", "closed");
    }
}
