package de.mhus.vance.brain.history;

import java.util.Set;

/**
 * Receiver for marker tags emitted by the tool-dispatcher hook. Engines
 * provide a concrete sink that knows which {@code ChatMessageDocument}
 * to write the tags to — the dispatcher itself stays decoupled from
 * Mongo. See {@code planning/process-history-search.md} §5.
 *
 * <p>Implementations must be tolerant of being called multiple times
 * within a single turn (multiple tool calls per assistant message) and
 * should treat the input as additive. The {@link #NOOP} default is used
 * when no engine has wired the sink — the dispatcher then runs the
 * pure-functional compute but discards the result.
 */
@FunctionalInterface
public interface HistoryTagSink {

    /**
     * Emit a set of marker tags computed for the current tool invocation.
     * Implementations should swallow downstream errors — a failed tag
     * write must not cascade back to the tool result the LLM already
     * observed.
     */
    void emit(Set<String> tags);

    /**
     * Emit the detail of a tool call that <em>failed</em>. Separate from
     * {@link #emit} because the {@code ERROR} tag says only "something
     * in this turn failed" — the model replaying the turn later needs to
     * know <em>which</em> call failed and why, since tool results
     * themselves are never persisted.
     *
     * <p>Default is a no-op so sinks that only care about tags (and
     * {@link #NOOP}) stay valid without change.
     */
    default void emitFailure(String toolName, @org.jspecify.annotations.Nullable String message) {
        // no-op by default
    }

    /** No-op sink — the default when no engine has wired a real one. */
    HistoryTagSink NOOP = tags -> {};
}
