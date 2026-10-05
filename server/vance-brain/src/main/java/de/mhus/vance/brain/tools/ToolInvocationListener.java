package de.mhus.vance.brain.tools;

import org.jspecify.annotations.Nullable;

/**
 * Hook called by {@link ContextToolsApi#invoke(String, java.util.Map)}
 * around every tool dispatch. Implementations are intentionally tiny so
 * the per-call overhead stays at "two virtual calls" — the listener is
 * the integration point for user-facing status pings, structured audit,
 * and similar cross-cutting concerns.
 *
 * <p>The listener never sees tool arguments or results: those carry
 * potentially large or sensitive payloads and don't belong in a
 * narrow-purpose hook. What it does get is the curated <em>teaser</em> —
 * the capped subject line the dispatch layer distilled from the params
 * (see {@code ToolTeasers} in vance-toolpack): enough to render
 * "file_write → src/Main.java", never enough to leak a payload. Callers
 * that need the raw data should subclass the tool itself or hook the
 * dispatcher directly.
 */
public interface ToolInvocationListener {

    /**
     * Called immediately before {@code dispatcher.invoke(...)} runs.
     *
     * @param callTeaser distilled subject of the call, or {@code null}
     *                    when the tool has no meaningful subject
     */
    void before(String toolName, @Nullable String callTeaser);

    /**
     * Called after the dispatcher returned (or threw). {@code error} is
     * {@code null} on success, otherwise the throwable that bubbled out
     * — listeners should NOT swallow it; the caller will rethrow.
     *
     * @param outcomeTeaser distilled summary of the result (e.g.
     *                      "Wrote 1234 chars"), or {@code null} — always
     *                      {@code null} on the error path
     */
    void after(String toolName, long elapsedMs, @Nullable String outcomeTeaser, @Nullable Throwable error);

    /**
     * Called instead of {@link #before} when a wrapper dispatches to its
     * backend ({@code file_read} → {@code client_file_read} via
     * {@link ContextToolsApi#invokeDelegate}). Default: indistinguishable
     * from any other dispatch. By contract a delegate leg is always
     * nested inside its wrapper's dispatch, so listeners that report the
     * wrapper call already cover it.
     *
     * <p>Two listeners opt out deliberately. The demand counter, because
     * the delegated leg is a mechanical consequence of the wrapper call,
     * not a second thing the model asked for — counting both made every
     * wrapper call show up twice in {@code tool_usage_stats}. And the
     * progress pings, because the wrapper's ping already names the same
     * subject — a second, identical line for {@code client_file_write}
     * would just be noise in the user's activity list.
     */
    default void beforeDelegate(String toolName, @Nullable String callTeaser) {
        before(toolName, callTeaser);
    }

    /** Delegate counterpart of {@link #after}; see {@link #beforeDelegate}. */
    default void afterDelegate(
            String toolName, long elapsedMs, @Nullable String outcomeTeaser, @Nullable Throwable error) {
        after(toolName, elapsedMs, outcomeTeaser, error);
    }

    /** Listener that does nothing — used when no observation is wired. */
    ToolInvocationListener NOOP = new ToolInvocationListener() {
        @Override
        public void before(String toolName, @Nullable String callTeaser) {}

        @Override
        public void after(String toolName, long elapsedMs, @Nullable String outcomeTeaser, @Nullable Throwable error) {}
    };
}
