package de.mhus.vance.brain.tools.builtins;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Lists the non-primary tools visible in the caller's scope, with an
 * optional substring filter on name or description. Pairs with
 * {@link DescribeToolTool} and {@link InvokeToolTool} so the LLM can
 * pull rarely-used tools into the conversation on demand.
 *
 * <p>Uses {@link ObjectProvider} so the dispatcher bean is resolved
 * lazily — otherwise Tool → ToolDispatcher → ServerToolSource → Tool
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
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String query = params == null ? null : (String) params.get("query");
        String needle = query == null ? null : query.toLowerCase();
        boolean includePrimary = params != null
                && Boolean.TRUE.equals(params.get("includePrimary"));

        List<ToolSpec> all = ToolDispatcher.specs(
                dispatcher.getObject().resolveAll(ctx));
        List<Map<String, Object>> matches = all.stream()
                .filter(t -> includePrimary || !t.isPrimary())
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tools", matches);
        out.put("count", matches.size());
        return out;
    }
}
