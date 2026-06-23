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
 * Returns the current attribute map of the paired Trillian-User
 * worker. Read-only — useful for Control to recap state to the
 * human or to verify a previous {@code user_attr_set} took.
 */
@Component
@RequiredArgsConstructor
public class UserAttrListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of());

    private final TrillianInternalApi api;

    @Override
    public String name() {
        return "user_attr_list";
    }

    @Override
    public String description() {
        return "List all attributes currently set on the paired "
                + "Trillian-User worker.";
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
        return Set.of("read-only");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianControlEngine.ROLE_TRILLIAN_CONTROL);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("user_attr_list requires a process scope");
        }
        Optional<ThinkProcessDocument> peerOpt = api.findPeer(ctx.processId());
        if (peerOpt.isEmpty()) {
            throw new ToolException(
                    "No Trillian-User peer found — this tool is only available "
                            + "inside a Trillian-Control session");
        }
        Map<String, Object> attrs = TrillianInternalApi.readAttributes(peerOpt.get());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attributes", attrs);
        out.put("count", attrs.size());
        return out;
    }
}
