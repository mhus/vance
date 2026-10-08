package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.List;
import java.util.Map;
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

    private final de.mhus.vance.brain.trillian.TrillianOwnSessions ownSessions;
    /** Breaks cycles through the session lifecycle service. */
    private final ObjectProvider<SessionLifecycleService> lifecycleProvider;

    @Override
    public String name() {
        return "session_close";
    }

    @Override
    public String description() {
        return "Leave a session you opened with session_open: it closes with its processes.";
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
        return Set.of(ToolLabels.INTERNAL, "executive");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianUserEngine.ROLE_TRILLIAN_USER);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("sessionId");
        SessionDocument session = ownSessions.requireOwn(
                raw instanceof String sid ? sid : null, ctx, de.mhus.vance.shared.permission.Action.EXECUTE);
        lifecycleProvider.getObject().closeWithCascade(session.getSessionId());
        return Map.of("sessionId", session.getSessionId(), "status", "closed");
    }
}
