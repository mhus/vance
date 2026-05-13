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
public class HookCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("event", Map.of(
                "type", "string",
                "description", "Wire-form event name, e.g. 'process.completed'."));
        props.put("name", Map.of(
                "type", "string",
                "description", "Hook name — lowercase, alphanumeric + '_-', max 64 chars."));
        props.put("yaml", Map.of(
                "type", "string",
                "description", "Full YAML body. Required: 'type' (js|llm). "
                        + "JS: 'script'. LLM: 'prompt' + 'model'."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("event", "name", "yaml"));
    }

    private final HookService hookService;
    private final HookToolSupport support;

    @Override public String name() { return "hook_create"; }

    @Override public String description() {
        return "Create a new hook in the current project. Fails if a hook "
                + "with the same (event, name) already exists. Use hook_update "
                + "to modify an existing one.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write", "hook"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("hook_create requires a project scope");
        }
        HookEventName event = HookToolSupport.parseEvent(
                support.stringOrThrow(params, "event"));
        String name = HookToolSupport.normalizeName(
                support.stringOrThrow(params, "name"));
        String yaml = support.stringOrThrow(params, "yaml");

        if (hookService.findOne(ctx.tenantId(), ctx.projectId(), event, name).isPresent()) {
            throw new ToolException(
                    "hook '" + event.wireName() + "/" + name + "' already exists in this project");
        }
        HookDef saved;
        try {
            saved = hookService.save(
                    ctx.tenantId(), ctx.projectId(), event, name, yaml, ctx.userId());
        } catch (HookParseException ex) {
            throw new ToolException("hook YAML rejected: " + ex.getMessage());
        }
        Map<String, Object> resp = new LinkedHashMap<>(support.shape(ctx.tenantId(), saved));
        resp.put("created", true);
        return resp;
    }
}
