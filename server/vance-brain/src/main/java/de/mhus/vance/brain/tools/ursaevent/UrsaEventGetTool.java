package de.mhus.vance.brain.tools.ursaevent;

import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
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

/**
 * Read the full YAML body + metadata of one event. Resolves through the
 * standard project → _tenant cascade.
 */
@Component
@RequiredArgsConstructor
public class UrsaEventGetTool implements Tool {

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

    private final UrsaEventLoader loader;
    private final UrsaEventToolSupport support;

    @Override public String name() { return "event_get"; }

    @Override public String description() {
        return "Return the full YAML body and resolved metadata of one event. "
                + "Resolves through the project → _tenant cascade.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("read-only", "events"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("event_get requires a project scope");
        }
        String name = UrsaEventToolSupport.normalizeName(stringOrThrow(params, "name"));
        Optional<ResolvedUrsaEvent> hit = loader.load(ctx.tenantId(), ctx.projectId(), name);
        if (hit.isEmpty()) {
            throw new ToolException("event '" + name + "' not found");
        }
        return support.shapeFull(hit.get());
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
