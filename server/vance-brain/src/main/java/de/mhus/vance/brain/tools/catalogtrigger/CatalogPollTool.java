package de.mhus.vance.brain.tools.catalogtrigger;

import de.mhus.vance.brain.servicecatalog.CatalogPollService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * One tick of a polling trigger: asks the service and returns the entries not seen before as {@code items}, plus
 * {@code pending}, which {@code catalog_poll_commit} remembers afterwards. Reads only. When the service answers
 * with an error, that answer is returned as it is ({@code ok: false, status}) so a {@code tool_task} sorts it
 * into rate-limited, sign-in again, and so on.
 */
@Component
@RequiredArgsConstructor
public class CatalogPollTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "name",
                            Map.of("type", "string", "description", "Name of the trigger (its scheduler and state)."),
                            "from",
                            Map.of("type", "string", "description", "<service>/<trigger> of the catalog."),
                            "inputs",
                            Map.of("type", "object", "description", "What was said when the trigger was switched on.")),
            "required", List.of("name", "from"));

    private final CatalogPollService polls;

    @Override
    public String name() {
        return "catalog_poll";
    }

    @Override
    public String description() {
        return "Ask a service for what is new since the last poll (polling trigger of the service catalog). "
                + "Returns items (entries not seen before) and pending, which catalog_poll_commit remembers. "
                + "Used by the workflow generated for a polling trigger.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.OPERATOR, "events");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) throw new ToolException("catalog_poll requires a project scope");
        CatalogPollService.Polled polled = polls.poll(
                ctx.tenantId(),
                ctx.projectId(),
                ctx.userId(),
                text(params, "name"),
                text(params, "from"),
                inputs(params));
        if (polled.failure() != null) {
            return polled.failure();
        }
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("ids", polled.pending().ids());
        if (polled.pending().cursor() != null)
            pending.put("cursor", polled.pending().cursor());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", polled.items());
        out.put("count", polled.items().size());
        out.put("pending", pending);
        return out;
    }

    private static Map<String, String> inputs(Map<String, Object> params) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params != null && params.get("inputs") instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        }
        return out;
    }

    private static String text(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
