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
     * Names of the tools this bus can actually invoke in the current
     * engine scope — i.e. calling {@link #invoke(String, Map)} with any
     * other name is expected to fail. Discovery tools ({@code find_tools})
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
