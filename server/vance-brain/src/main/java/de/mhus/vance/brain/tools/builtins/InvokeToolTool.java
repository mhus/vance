package de.mhus.vance.brain.tools.builtins;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Invokes any tool visible in the caller's scope by name. Lets the LLM
 * reach a secondary tool in one hop after it has been discovered with
 * {@code find_tools} / {@code describe_tool}, without needing a
 * dedicated tool binding on the LLM side for every tool.
 *
 * <p>Calling a primary tool through here is allowed but pointless — the
 * primary is already directly callable.
 */
@Component
public class InvokeToolTool implements Tool {

    @SuppressWarnings("unchecked")
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Name of the tool to invoke."),
                    "params", Map.of(
                            "type", "object",
                            "description",
                                    "Parameter object for the tool, matching "
                                            + "its paramsSchema.")),
            "required", List.of("name"));

    private final ObjectProvider<ToolDispatcher> dispatcher;

    public InvokeToolTool(ObjectProvider<ToolDispatcher> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public String name() {
        return "invoke_tool";
    }

    @Override
    public String description() {
        return "Invokes any visible tool by name with a parameter object. "
                + "Use this to call a tool discovered through find_tools.";
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
    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String name = params == null ? null : (String) params.get("name");
        if (name == null || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        Object rawParams = params.get("params");
        Map<String, Object> inner;
        if (rawParams == null) {
            inner = Map.of();
        } else if (rawParams instanceof Map<?, ?> m) {
            inner = (Map<String, Object>) m;
        } else {
            throw new ToolException("'params' must be an object");
        }
        return dispatcher.getObject().invoke(name, inner, ctx);
    }
}
