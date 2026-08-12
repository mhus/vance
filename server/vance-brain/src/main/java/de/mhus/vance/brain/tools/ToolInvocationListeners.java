package de.mhus.vance.brain.tools;

import de.mhus.vance.brain.tools.budget.ToolFamily;
import de.mhus.vance.shared.toolusage.ToolUsageService;
import java.util.List;
import java.util.function.Consumer;
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
     *
     * <p><b>Forwards the delegate hooks as delegate hooks.</b> Relying on
     * their default (which routes to {@code before}/{@code after}) would
     * flatten the distinction right here: every child would see a
     * delegated dispatch as a normal one, and a listener that opted out of
     * delegated legs would still be called. Measured 2026-08-12: the
     * demand counter kept recording {@code client_file_edit} next to
     * {@code file_edit} even though the recorder no-ops on delegates —
     * because this composite never asked it.
     */
    public static ToolInvocationListener of(ToolInvocationListener... listeners) {
        List<ToolInvocationListener> chain = List.of(listeners);
        return new ToolInvocationListener() {
            @Override
            public void before(String toolName) {
                fanOut(chain, l -> l.before(toolName));
            }

            @Override
            public void after(String toolName, long elapsedMs, @Nullable Throwable error) {
                fanOut(chain, l -> l.after(toolName, elapsedMs, error));
            }

            @Override
            public void beforeDelegate(String toolName) {
                fanOut(chain, l -> l.beforeDelegate(toolName));
            }

            @Override
            public void afterDelegate(
                    String toolName, long elapsedMs, @Nullable Throwable error) {
                fanOut(chain, l -> l.afterDelegate(toolName, elapsedMs, error));
            }
        };
    }

    /** Calls every listener, swallowing individual observer failures. */
    private static void fanOut(
            List<ToolInvocationListener> chain, Consumer<ToolInvocationListener> call) {
        for (ToolInvocationListener l : chain) {
            try {
                call.accept(l);
            } catch (RuntimeException ignored) {
                // observer failure — keep dispatching
            }
        }
    }

    /**
     * Counts successful dispatches into {@link ToolUsageService}, which
     * feeds the tool-surface budget's tie-break ordering.
     *
     * <p>Two things are deliberately not counted:
     *
     * <ul>
     *   <li><b>Failures.</b> A tool that raised didn't do the job the model
     *       wanted, and a broken tool should not earn a manifest slot by
     *       being retried.</li>
     *   <li><b>Delegated legs.</b> A wrapper call ({@code file_read}) is
     *       counted once; the backend it dispatches to
     *       ({@code client_file_read}) is the same ask, not a second one.
     *       Counting both showed every wrapper call twice in
     *       {@code tool_usage_stats} — measured 2026-08-12: 9 pairs with
     *       identical counts.</li>
     * </ul>
     *
     * @param recipeName role the counters are attributed to (see
     *                   {@link ToolUsageService})
     */
    public static ToolInvocationListener usageRecorder(
            ToolUsageService usageService,
            @Nullable String tenantId,
            @Nullable String projectId,
            @Nullable String recipeName) {
        return new ToolInvocationListener() {
            @Override
            public void before(String toolName) {
                // nothing to do — counting happens once the call landed
            }

            @Override
            public void after(String toolName, long elapsedMs, @Nullable Throwable error) {
                if (error != null) return;
                usageService.recordCall(
                        tenantId, projectId, recipeName, toolName, ToolFamily.of(toolName));
            }

            @Override
            public void beforeDelegate(String toolName) {
                // no-op: the wrapper's own call is the demand signal
            }

            @Override
            public void afterDelegate(
                    String toolName, long elapsedMs, @Nullable Throwable error) {
                // no-op — see beforeDelegate
            }
        };
    }
}
