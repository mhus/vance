package de.mhus.vance.brain.tools.context;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Returns the current invocation context — user, tenant, project,
 * session, and process ids. Useful when an engine needs to address
 * the user (e.g. {@code inbox_post(targetUserId=...)}) or when it
 * just wants to confirm where it is.
 *
 * <p>Primary because every chat-orchestrator (Arthur) wants this on
 * every turn — it's tiny output and saves the LLM from guessing.
 */
@Component
public class WhoamiTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    @Override
    public String name() {
        return "whoami";
    }

    @Override
    public String description() {
        return "Get the current user, tenant, project, session, and "
                + "process ids — handy for `inbox_post` (targetUserId), "
                + "delegations, audit notes.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", ctx.userId());
        out.put("tenantId", ctx.tenantId());
        out.put("projectId", ctx.projectId());
        out.put("sessionId", ctx.sessionId());
        out.put("processId", ctx.processId());
        return out;
    }
}
