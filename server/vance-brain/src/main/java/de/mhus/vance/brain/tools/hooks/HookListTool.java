package de.mhus.vance.brain.tools.hooks;

import de.mhus.vance.brain.hooks.HookDef;
import de.mhus.vance.brain.hooks.HookService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HookListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<>(),
            "required", List.of());

    private final HookService hookService;
    private final HookToolSupport support;

    @Override public String name() { return "hook_list"; }

    @Override public String description() {
        return "List all hooks visible to the current project — from "
                + "both the project itself and the tenant-wide _vance/hooks/ "
                + "folder. Returns one entry per (event, name) with type, "
                + "enabled flag, source, and last-run summary.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("read-only", "hook"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("hook_list requires a project scope");
        }
        List<HookDef> defs = hookService.listAll(ctx.tenantId(), ctx.projectId());
        List<Map<String, Object>> shaped = new ArrayList<>(defs.size());
        for (HookDef def : defs) {
            shaped.add(support.shape(ctx.tenantId(), def));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("hooks", shaped);
        return resp;
    }
}
