package de.mhus.vance.toolpack;

import java.util.Map;

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
}
