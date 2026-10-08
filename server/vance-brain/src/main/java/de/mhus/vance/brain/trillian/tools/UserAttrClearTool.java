package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianControlEngine;
import de.mhus.vance.brain.trillian.TrillianInternalApi;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Removes all attributes from the paired Trillian-User worker.
 * Use when starting a fresh persona / re-configuring the worker
 * mid-session. The worker keeps running; only the attributes are
 * wiped.
 */
@Component
@RequiredArgsConstructor
public class UserAttrClearTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of());

    private final TrillianInternalApi api;

    @Override
    public String name() {
        return "user_attr_clear";
    }

    @Override
    public String description() {
        return "Forget every attribute set on this Trillian. The work "
                + "carries on; only who you are supposed to be is reset.";
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
        return Set.of(TrillianControlEngine.ROLE_TRILLIAN_CONTROL);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("user_attr_clear requires a process scope");
        }
        int cleared = api.clearPeerAttributes(ctx.processId());
        if (cleared < 0) {
            throw new ToolException(
                    "No Trillian-User peer found — this tool is only available " + "inside a Trillian-Control session");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", cleared);
        return out;
    }
}
