package de.mhus.vance.brain.tools.hooks;

import de.mhus.vance.api.hooks.HookEventName;
import de.mhus.vance.brain.hooks.HookDef;
import de.mhus.vance.brain.hooks.HookService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HookGetTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("event", Map.of(
                "type", "string",
                "description", "Wire-form event name, e.g. 'process.completed'."));
        props.put("name", Map.of(
                "type", "string",
                "description", "Hook name (filename without .yaml)."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("event", "name"));
    }

    private final HookService hookService;
    private final HookToolSupport support;

    @Override public String name() { return "hook_get"; }

    @Override public String description() {
        return "Fetch the full YAML body of one hook in the current project.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("read-only", "hook"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("hook_get requires a project scope");
        }
        HookEventName event = HookToolSupport.parseEvent(
                support.stringOrThrow(params, "event"));
        String name = HookToolSupport.normalizeName(
                support.stringOrThrow(params, "name"));
        Optional<HookDef> found = hookService.findOne(
                ctx.tenantId(), ctx.projectId(), event, name);
        if (found.isEmpty()) {
            throw new ToolException(
                    "Hook '" + event.wireName() + "/" + name + "' not found in project");
        }
        Map<String, Object> resp = new LinkedHashMap<>(support.shape(ctx.tenantId(), found.get()));
        resp.put("yaml", found.get().yamlBody());
        return resp;
    }
}
