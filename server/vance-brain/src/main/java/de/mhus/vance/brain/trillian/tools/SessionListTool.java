package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The sessions this Trillian has open in projects right now (A6/D6) — its
 * "where am I" view. Only its own; other people's sessions are not listed.
 */
@Component
@RequiredArgsConstructor
public class SessionListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of());

    private final de.mhus.vance.brain.trillian.TrillianOwnSessions ownSessions;

    @Override
    public String name() {
        return "session_list";
    }

    @Override
    public String description() {
        return "List the sessions you currently have open in projects: "
                + "where, why, how old, last activity. Your own only.";
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
        return Set.of(ToolLabels.INTERNAL, "read-only");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianUserEngine.ROLE_TRILLIAN_USER);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.userId() == null) {
            throw new ToolException("session_list requires a user scope");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SessionDocument s : ownSessions.openSessionsOf(ctx.tenantId(), ctx.userId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sessionId", s.getSessionId());
            row.put("projectId", s.getProjectId());
            row.put(
                    "collab",
                    de.mhus.vance.brain.trillian.TrillianOwnSessions.modeOf(s).name());
            if (s.getDisplayName() != null) {
                row.put("displayName", s.getDisplayName());
            }
            if (s.getStatus() != null) {
                row.put("status", s.getStatus().name());
            }
            if (s.getLastActivityAt() != null) {
                row.put("lastActivityAt", s.getLastActivityAt().toString());
            }
            rows.add(row);
        }
        return Map.of("sessions", rows);
    }
}
