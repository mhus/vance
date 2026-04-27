package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What an engine reports to its parent process at a life-cycle
 * transition (DONE / FAILED / STOPPED / BLOCKED). Returned by
 * {@link ThinkEngine#summarizeForParent(ThinkProcessDocument,
 * de.mhus.vance.api.thinkprocess.ProcessEventType)} and consumed
 * by {@link ParentNotificationListener} to shape the
 * {@code ProcessEvent} that lands in the parent's pending queue.
 *
 * <p>{@link #humanSummary} is the LLM-readable Markdown text — the
 * parent renders it inside a {@code <process-event>} XML wrapper for
 * its own LLM to consume.
 *
 * <p>{@link #payload} is optional structured side-channel data —
 * useful when the parent isn't an LLM but a deterministic
 * orchestrator (Vogon-style state-machine) that needs to feed
 * specific fields into a later phase. v1 typically leaves this
 * {@code null} since Arthur (chat) only consumes the human summary;
 * Vogon will start using it.
 */
public record ParentReport(
        String humanSummary,
        @Nullable Map<String, Object> payload) {

    /** Convenience for the common case — text-only, no payload. */
    public static ParentReport of(String humanSummary) {
        return new ParentReport(humanSummary, null);
    }
}
