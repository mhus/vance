package de.mhus.vance.brain.tools.hooks;

import de.mhus.vance.brain.ursahooks.UrsaHookService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HookRefreshTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<>(),
            "required", List.of());

    private final UrsaHookService ursaHookService;

    @Override public String name() { return "hook_refresh"; }

    @Override public String description() {
        return "Force a full re-read of all hooks for this project. Useful "
                + "after bulk document edits outside the tool path.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("admin", "hook"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("hook_refresh requires a project scope");
        }
        int count = ursaHookService.refresh(ctx.tenantId(), ctx.projectId());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("registered", count);
        return resp;
    }
}
