package de.mhus.vance.brain.tools.hooks;

import de.mhus.vance.api.hooks.HookEventName;
import de.mhus.vance.brain.hooks.HookDef;
import de.mhus.vance.brain.hooks.HookParseException;
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
public class HookUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("event", Map.of(
                "type", "string",
                "description", "Wire-form event name, e.g. 'process.completed'."));
        props.put("name", Map.of(
                "type", "string",
                "description", "Hook name (filename without .yaml)."));
        props.put("yaml", Map.of(
                "type", "string",
                "description", "Full replacement YAML body."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("event", "name", "yaml"));
    }

    private final HookService hookService;
    private final HookToolSupport support;

    @Override public String name() { return "hook_update"; }

    @Override public String description() {
        return "Replace the YAML body of an existing hook. Creates a "
                + "project-layer override if the current hook lives at the "
                + "_vance tier.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write", "hook"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("hook_update requires a project scope");
        }
        HookEventName event = HookToolSupport.parseEvent(
                support.stringOrThrow(params, "event"));
        String name = HookToolSupport.normalizeName(
                support.stringOrThrow(params, "name"));
        String yaml = support.stringOrThrow(params, "yaml");

        HookDef saved;
        try {
            saved = hookService.save(
                    ctx.tenantId(), ctx.projectId(), event, name, yaml, ctx.userId());
        } catch (HookParseException ex) {
            throw new ToolException("hook YAML rejected: " + ex.getMessage());
        }
        Map<String, Object> resp = new LinkedHashMap<>(support.shape(ctx.tenantId(), saved));
        resp.put("updated", true);
        return resp;
    }
}
