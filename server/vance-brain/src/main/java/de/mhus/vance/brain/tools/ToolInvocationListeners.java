package de.mhus.vance.brain.tools;

import de.mhus.vance.brain.tools.budget.ToolFamily;
import de.mhus.vance.shared.toolusage.ToolUsageService;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Composition helpers for {@link ToolInvocationListener}. Two independent
 * cross-cutting concerns hang off every tool dispatch today — user-facing
 * progress pings and usage counting — and neither should have to know
 * about the other.
 */
public final class ToolInvocationListeners {

    private ToolInvocationListeners() {}

    /**
     * Fan out to all listeners in order. A listener that throws must not
     * take down the tool call or stop the remaining listeners: these are
     * observers, and the dispatch is the thing that matters.
     */
    public static ToolInvocationListener of(ToolInvocationListener... listeners) {
        List<ToolInvocationListener> chain = List.of(listeners);
        return new ToolInvocationListener() {
            @Override
            public void before(String toolName) {
                for (ToolInvocationListener l : chain) {
                    try {
                        l.before(toolName);
                    } catch (RuntimeException ignored) {
                        // observer failure — keep dispatching
                    }
                }
            }

            @Override
            public void after(String toolName, long elapsedMs, @Nullable Throwable error) {
                for (ToolInvocationListener l : chain) {
                    try {
                        l.after(toolName, elapsedMs, error);
                    } catch (RuntimeException ignored) {
                        // observer failure — keep dispatching
                    }
                }
            }
        };
    }

    /**
     * Counts successful dispatches into {@link ToolUsageService}, which
     * feeds the tool-surface budget's tie-break ordering. Failures are not
     * counted: a tool that raised didn't do the job the model wanted, and
     * a broken tool should not earn a manifest slot by being retried.
     */
    public static ToolInvocationListener usageRecorder(
            ToolUsageService usageService,
            @Nullable String tenantId,
            @Nullable String projectId) {
        return new ToolInvocationListener() {
            @Override
            public void before(String toolName) {
                // nothing to do — counting happens once the call landed
            }

            @Override
            public void after(String toolName, long elapsedMs, @Nullable Throwable error) {
                if (error != null) return;
                usageService.recordCall(
                        tenantId, projectId, toolName, ToolFamily.of(toolName));
            }
        };
    }
}
