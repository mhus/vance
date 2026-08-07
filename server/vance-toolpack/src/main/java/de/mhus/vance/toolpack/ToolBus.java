package de.mhus.vance.toolpack;

import java.util.Map;
import java.util.Set;

/**
 * Minimal "call another tool" surface that {@link Tool} implementations
 * can request via the 3-arg {@link Tool#invoke} overload. Lives in
 * vance-toolpack so the {@code Tool} interface can declare the third
 * arg without depending on the heavier server-side
 * {@code ContextToolsApi}.
 *
 * <p>Server-side: implemented by
 * {@code de.mhus.vance.brain.tools.ContextToolsApi} — full dispatcher
 * with allow-list, primary/deferred classification, listener
 * callbacks. Foot-side: a no-op bus is sufficient for tools that
 * don't call siblings (i.e. all foot tools today).
 *
 * <p>Implementations enforce their own visibility rules — calling
 * {@code invoke(name, params)} on a tool the caller doesn't have
 * permission for is expected to throw {@link ToolException}.
 */
public interface ToolBus {

    /** No-op bus that always rejects invocations. Useful as a default. */
    ToolBus NOOP = (name, params) -> {
        throw new ToolException("ToolBus.NOOP cannot dispatch '" + name + "' — "
                + "no sibling-tool surface bound for this invocation");
    };

    /**
     * Dispatches a sibling tool call by name. The caller is the
     * currently-running tool that received the bus from its
     * {@link Tool#invoke(Map, ToolInvocationContext, ToolBus)} entry.
     */
    Map<String, Object> invoke(String name, Map<String, Object> params);

    /**
     * Dispatches a sibling call that the <b>wrapper made, not the LLM</b>
     * — a mechanical delegation to a backend the model never named.
     *
     * <p>The distinction matters for deferred tools. {@link #invoke} treats
     * a call to a deferred tool as the LLM discovering it and activates it,
     * so it appears in the manifest from the next turn on. For a dispatcher
     * wrapper that is exactly wrong: one {@code file_read} would promote
     * {@code work_file_read} into the prompt and re-create the
     * two-candidates-for-one-job confusion the wrapper exists to prevent.
     *
     * <p>Implementations must still enforce their allow-set — this widens
     * nothing, it only suppresses the activation side-effect. Default
     * delegates to {@link #invoke} so buses that don't distinguish the two
     * keep working.
     */
    default Map<String, Object> invokeDelegate(String name, Map<String, Object> params) {
        return invoke(name, params);
    }

    /**
     * Names of the tools this bus can actually invoke in the current
     * engine scope — i.e. calling {@link #invoke(String, Map)} with any
     * other name is expected to fail. Discovery tools ({@code tool_list})
     * use this to avoid advertising tools the engine would reject.
     *
     * <p>An <b>empty</b> set means "no restriction known" — either an
     * unrestricted engine or a bus that doesn't track a scope; callers
     * must treat empty as "don't filter", not "nothing is invocable".
     * Default is empty so existing {@link ToolBus} implementations
     * (including {@link #NOOP} and lambdas) keep compiling.
     */
    default Set<String> invocableToolNames() {
        return Set.of();
    }
}
