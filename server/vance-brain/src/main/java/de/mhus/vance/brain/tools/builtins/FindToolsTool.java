package de.mhus.vance.brain.tools.builtins;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Lists the non-primary tools visible in the caller's scope, with an
 * optional substring filter on name or description. Pairs with
 * {@link DescribeToolTool} and {@link InvokeToolTool} so the LLM can
 * pull rarely-used tools into the conversation on demand.
 *
 * <p>Uses {@link ObjectProvider} so the dispatcher bean is resolved
 * lazily — otherwise Tool → ToolDispatcher → BuiltInToolSource → Tool
 * would cycle at construction.
 */
@Component
public class FindToolsTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of(
                            "type", "string",
                            "description",
                                    "Optional case-insensitive substring matched "
                                            + "against name and description."),
                    "includePrimary", Map.of(
                            "type", "boolean",
                            "description",
                                    "If true, also lists primary tools. Default false.")),
            "required", List.of());

    private final ObjectProvider<ToolDispatcher> dispatcher;

    public FindToolsTool(ObjectProvider<ToolDispatcher> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public String name() {
        return "find_tools";
    }

    @Override
    public String description() {
        return "Finds non-primary tools by optional name/description filter. "
                + "Use this to discover capabilities before calling them.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public boolean contributesPrak() {
        // Discovery only — no content the assistant synthesises into insight.
        return false;
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        // No bus bound → no engine allow-set to scope by (foot-side /
        // legacy dispatch). List the whole context-dispatchable pool.
        return find(params, ctx, Set.of());
    }

    @Override
    public Map<String, Object> invoke(
            Map<String, Object> params, ToolInvocationContext ctx, ToolBus bus) {
        // Scope to what the engine can actually invoke, so we never
        // advertise a tool whose call would hard-fail with
        // "not available to this engine" (misleads the model into
        // wasted calls and hallucinated success). Empty set → the
        // engine is unrestricted, so don't filter.
        return find(params, ctx, bus.invocableToolNames());
    }

    private Map<String, Object> find(
            Map<String, Object> params, ToolInvocationContext ctx, Set<String> invocable) {
        String query = params == null ? null : (String) params.get("query");
        boolean includePrimary = params != null
                && Boolean.TRUE.equals(params.get("includePrimary"));

        List<ToolSpec> all = ToolDispatcher.specs(
                dispatcher.getObject().resolveAll(ctx));
        List<Map<String, Object>> matches = filterMatches(all, query, includePrimary, invocable);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tools", matches);
        out.put("count", matches.size());
        return out;
    }

    /**
     * Pure filtering step — extracted so the visibility/scoping rules
     * are unit-testable without a live dispatcher. When {@code invocable}
     * is non-empty, only tools in that set are returned (the engine
     * allow-set); an empty {@code invocable} means "no scope filter".
     */
    static List<Map<String, Object>> filterMatches(
            List<ToolSpec> all, String query, boolean includePrimary, Set<String> invocable) {
        String needle = query == null ? null : query.toLowerCase();
        return all.stream()
                .filter(t -> includePrimary || !t.isPrimary())
                .filter(t -> invocable.isEmpty() || invocable.contains(t.getName()))
                .filter(t -> needle == null
                        || t.getName().toLowerCase().contains(needle)
                        || (t.getDescription() != null
                                && t.getDescription().toLowerCase().contains(needle)))
                .map(t -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", t.getName());
                    row.put("description", t.getDescription());
                    row.put("primary", t.isPrimary());
                    return row;
                })
                .toList();
    }
}
