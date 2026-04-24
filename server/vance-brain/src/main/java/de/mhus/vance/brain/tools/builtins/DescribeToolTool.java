package de.mhus.vance.brain.tools.builtins;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Returns the full {@link de.mhus.vance.api.tools.ToolSpec} for a named
 * tool, including its parameter schema. Intended as the second step of
 * the find/describe/invoke discovery pattern.
 */
@Component
public class DescribeToolTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Name of the tool to describe.")),
            "required", List.of("name"));

    private final ObjectProvider<ToolDispatcher> dispatcher;

    public DescribeToolTool(ObjectProvider<ToolDispatcher> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public String name() {
        return "describe_tool";
    }

    @Override
    public String description() {
        return "Returns the full specification (description + parameter schema) "
                + "for a named tool.";
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
        String name = params == null ? null : (String) params.get("name");
        if (name == null || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        ToolDispatcher.Resolved r = dispatcher.getObject().resolve(name, ctx)
                .orElseThrow(() -> new ToolException("Unknown tool: " + name));
        Tool tool = r.tool();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", tool.name());
        out.put("description", tool.description());
        out.put("primary", tool.primary());
        out.put("source", r.source().sourceId());
        out.put("paramsSchema", tool.paramsSchema());
        return out;
    }
}
