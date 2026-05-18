package de.mhus.vance.brain.script.cortex;

import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the tool allow-list that Script Cortex
 * exposes to both
 *
 * <ul>
 *   <li>the executor — as the {@code ContextToolsApi} allow-set used
 *       when a Cortex-authored script runs, and</li>
 *   <li>Deep Thought — as the {@code engineParams.scriptAllowedTools}
 *       list whose names land in the drafting/framing prompt's
 *       {@code toolInventory} block.</li>
 * </ul>
 *
 * <p>Without symmetry the two sides would drift: DT generates code
 * that calls {@code vance.tools.call('foo', …)} based on its prompt
 * inventory; the executor either rejects {@code foo} (if it isn't
 * in the allow-set) or silently lets it through (if it is). Both
 * sides reading from this provider keeps generated code runnable.
 *
 * <p>v1 policy: every tool the {@link ToolDispatcher} resolves for
 * the given scope. The dispatcher's own visibility rules (profile
 * gates, project filters) are honoured — Cortex doesn't widen them.
 * Per-tool permission checks still run at invoke-time inside the
 * tool's own handler.
 */
@Component
@RequiredArgsConstructor
public class ScriptCortexToolPolicy {

    private final ToolDispatcher dispatcher;

    /** Sorted, deduplicated list of tool names available in {@code scope}. */
    public List<String> availableTools(ToolInvocationContext scope) {
        List<ToolDispatcher.Resolved> resolved = dispatcher.resolveAll(scope);
        List<String> names = new ArrayList<>(resolved.size());
        for (ToolDispatcher.Resolved r : resolved) {
            names.add(r.tool().name());
        }
        names.sort(String::compareTo);
        return names;
    }

    /**
     * Convenience for callers that have tenant/project/user/session/
     * processId loose — builds the {@link ToolInvocationContext} on the
     * fly. {@code processId} is {@code null} for Cortex calls (no
     * think-process is bound to the editor surface).
     */
    public List<String> availableTools(
            String tenantId,
            @Nullable String projectId,
            @Nullable String sessionId,
            @Nullable String userId) {
        return availableTools(new ToolInvocationContext(
                tenantId, projectId, sessionId, /*processId*/ null, userId));
    }
}
