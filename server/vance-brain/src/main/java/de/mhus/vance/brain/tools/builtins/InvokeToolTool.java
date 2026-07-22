package de.mhus.vance.brain.tools.builtins;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Invokes any tool the caller's engine is allowed to use, by name. Lets
 * the LLM reach a secondary tool in one hop after it has been discovered
 * with {@code find_tools} / {@code describe_tool}, without needing a
 * dedicated tool binding on the LLM side for every tool.
 *
 * <p>Calling a primary tool through here is allowed but pointless — the
 * primary is already directly callable.
 *
 * <p><b>Authorization.</b> The invocation is routed through the engine's
 * bound {@link ToolBus} ({@code ContextToolsApi}), so the target name is
 * subjected to the <i>same</i> allow-set / engine-role / profile gate as
 * a direct LLM tool call — {@code invoke_tool} can never reach a tool the
 * engine itself is not allowed to invoke. Routing straight through the raw
 * {@code ToolDispatcher} (as an earlier version did) bypassed that gate
 * and let a role-gated tool such as {@code cross_process_create} be called
 * from an engine without the role. When no bus is bound (the 2-arg entry
 * point, e.g. a raw dispatcher call with no engine surface) there is no
 * gate to enforce, so the call is refused fail-closed.
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

    @Override
    public String name() {
        return "invoke_tool";
    }

    @Override
    public String description() {
        return "Invokes any tool your engine is allowed to use, by name, with "
                + "a parameter object. Use this to call a tool discovered "
                + "through find_tools.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    /**
     * 2-arg entry point has no {@link ToolBus} and therefore no allow-set
     * gate to enforce — refuse fail-closed rather than fall through to the
     * raw dispatcher. In normal operation {@code invoke_tool} is always
     * dispatched with a bus (see {@link ToolDispatcher#invoke} 4-arg
     * variant), so this path only triggers on a raw, surface-less call.
     */
    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        throw new ToolException(
                "invoke_tool requires an engine tool surface and cannot be "
                        + "called without one");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(
            Map<String, Object> params, ToolInvocationContext ctx, ToolBus bus) {
        String name = params == null ? null : (String) params.get("name");
        if (name == null || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        if (name.equals(name())) {
            throw new ToolException("invoke_tool cannot invoke itself");
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
        // Route through the engine's bound surface so the target name is
        // gated exactly like a direct LLM call (allow-set / engine-role /
        // profile). A tool outside the engine's allow-set is rejected by
        // the bus with a "not available to this engine" ToolException.
        return bus.invoke(name, inner);
    }
}
