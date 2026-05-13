package de.mhus.vance.brain.tools.hooks;

import de.mhus.vance.api.hooks.HookEventName;
import de.mhus.vance.brain.hooks.HookService;
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
public class HookDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("event", Map.of("type", "string"));
        props.put("name", Map.of("type", "string"));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("event", "name"));
    }

    private final HookService hookService;
    private final HookToolSupport support;

    @Override public String name() { return "hook_delete"; }

    @Override public String description() {
        return "Move a hook into the project trash. The hook stops firing "
                + "immediately. Tenant-tier hooks shadowed by the same name "
                + "become visible again.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write", "hook"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("hook_delete requires a project scope");
        }
        HookEventName event = HookToolSupport.parseEvent(
                support.stringOrThrow(params, "event"));
        String name = HookToolSupport.normalizeName(
                support.stringOrThrow(params, "name"));
        boolean removed = hookService.delete(ctx.tenantId(), ctx.projectId(), event, name);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("event", event.wireName());
        resp.put("name", name);
        resp.put("deleted", removed);
        return resp;
    }
}
