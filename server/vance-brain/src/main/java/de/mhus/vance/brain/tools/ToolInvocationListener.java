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
 * narrow-purpose hook. Callers that need them should subclass the tool
 * itself or hook the dispatcher directly.
 */
public interface ToolInvocationListener {

    /** Called immediately before {@code dispatcher.invoke(...)} runs. */
    void before(String toolName);

    /**
     * Called after the dispatcher returned (or threw). {@code error} is
     * {@code null} on success, otherwise the throwable that bubbled out
     * — listeners should NOT swallow it; the caller will rethrow.
     */
    void after(String toolName, long elapsedMs, @Nullable Throwable error);

    /** Listener that does nothing — used when no observation is wired. */
    ToolInvocationListener NOOP = new ToolInvocationListener() {
        @Override
        public void before(String toolName) {
        }

        @Override
        public void after(String toolName, long elapsedMs, @Nullable Throwable error) {
        }
    };
}
