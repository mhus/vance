package de.mhus.vance.brain.tools.ursaevent;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Delete the project-local copy of an event. A cascade-resolved tenant
 * entry of the same name is unaffected — same semantics as
 * {@code scheduler_delete}.
 */
@Component
@RequiredArgsConstructor
public class UrsaEventDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of(
                "type", "string",
                "description", "Event name (without .yaml suffix)."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("name"));
    }

    private final UrsaEventToolSupport support;

    @Override public String name() { return "event_delete"; }

    @Override public String description() {
        return "Delete the project-local copy of an event. Cascade-resolved "
                + "tenant entries with the same name are untouched. Response "
                + "carries 'deleted: true|false' (false → no local entry "
                + "existed; the cascade entry, if any, remains visible).";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write", "events"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("event_delete requires a project scope");
        }
        String name = UrsaEventToolSupport.normalizeName(stringOrThrow(params, "name"));
        boolean deleted = support.deleteByName(ctx.tenantId(), ctx.projectId(), name);
        return Map.of("name", name, "deleted", deleted);
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
