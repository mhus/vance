package de.mhus.vance.brain.tools.worktarget;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/**
 * Base class for generic {@code file_*} / {@code exec_*} tools. Each
 * subclass declares its name, description, schema, plus the two
 * backend tool names to dispatch to (client vs work).
 *
 * <p>The 3-arg {@link #invoke(Map, ToolInvocationContext, ToolBus)}
 * overload routes via {@link WorkTargetDispatcher}; the 2-arg form
 * rejects calls without a bound bus — the wrappers cannot work
 * without sibling-call dispatch.
 */
@RequiredArgsConstructor
abstract class AbstractWorkTargetTool implements Tool {

    private final WorkTargetDispatcher dispatcher;

    /** Backend tool name when {@code WorkTarget.kind == CLIENT}. */
    protected abstract String clientBackend();

    /** Backend tool name when {@code WorkTarget.kind == WORK}. */
    protected abstract String workBackend();

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public final Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        // 2-arg path (Agrajag probes, internal callers). The dispatcher
        // falls back to ToolDispatcher when no bus is available.
        return dispatcher.dispatch(ctx, null, clientBackend(), workBackend(), params);
    }

    @Override
    public final Map<String, Object> invoke(
            Map<String, Object> params, ToolInvocationContext ctx, ToolBus bus) {
        return dispatcher.dispatch(ctx, bus, clientBackend(), workBackend(), params);
    }
}
