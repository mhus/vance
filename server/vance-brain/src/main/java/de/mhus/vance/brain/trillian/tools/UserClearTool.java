package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianControlEngine;
import de.mhus.vance.brain.trillian.TrillianInternalApi;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Drops every task_request event queued on the Trillian User
 * worker's inbox that hasn't been picked up yet. The worker itself
 * keeps running.
 */
@Component
@RequiredArgsConstructor
public class UserClearTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of());

    private final TrillianInternalApi api;

    @Override
    public String name() {
        return "user_clear";
    }

    @Override
    public String description() {
        return "Drop every queued task_request on the Trillian User "
                + "worker's inbox. The worker keeps running; use this "
                + "when the human changes direction completely.";
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
        return Set.of(TrillianControlEngine.ROLE_TRILLIAN_CONTROL);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("user_clear requires a process scope");
        }
        Optional<ThinkProcessDocument> peerOpt = api.findPeer(ctx.processId());
        if (peerOpt.isEmpty()) {
            throw new ToolException(
                    "No Trillian User peer process found — this tool is only available "
                            + "inside a Trillian-Control session");
        }
        ThinkProcessDocument peer = peerOpt.get();
        int cleared = api.clearPending(peer.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("peerProcessName", peer.getName());
        out.put("cleared", cleared);
        return out;
    }
}
