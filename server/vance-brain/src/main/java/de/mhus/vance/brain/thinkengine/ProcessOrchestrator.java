package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Sibling-process control surface for the running engine.
 *
 * <p>Kept minimal in v1 — start/steer/stop go through tools
 * ({@code process_create}, {@code process_steer}, {@code process_stop})
 * because those are the same paths the LLM uses. What's here is the
 * <em>engine-only</em> traffic that has no corresponding tool:
 *
 * <ul>
 *   <li>{@link #notifyParent(ProcessEventType, String, Map) notifyParent}
 *       — push a {@code PROCESS_EVENT} into the parent's pending
 *       inbox and trigger Auto-Wakeup. Used for mid-flight summary
 *       notes ("found 3 hits so far") between formal life-cycle
 *       transitions, which the {@code ParentNotificationListener}
 *       handles automatically.</li>
 *   <li>{@link #siblings()} — quick read of the other processes in
 *       this session, e.g. for an orchestrator deciding whether a
 *       worker is already running.</li>
 * </ul>
 */
public interface ProcessOrchestrator {

    /**
     * Appends a {@code PROCESS_EVENT} to this process's parent
     * (if it has one) and schedules a wake-up turn for the parent's
     * lane. No-op when the current process is a top-level (no
     * parent) — returns {@code false} in that case so callers can
     * tell the difference between "queued" and "no recipient".
     *
     * @param type           event flavour — typically {@code SUMMARY}
     *                       for engine-driven progress; life-cycle
     *                       transitions are emitted automatically
     * @param humanSummary   short, LLM-readable summary
     * @param payload        optional structured side-channel data
     * @return {@code true} when the event was queued, {@code false}
     *         when the current process has no parent or the parent
     *         row was missing
     */
    boolean notifyParent(
            ProcessEventType type,
            String humanSummary,
            @Nullable Map<String, Object> payload);

    /**
     * Returns the other think-processes in this session — i.e. all
     * processes with the same {@code sessionId} except the current
     * one. Empty list when the current process is the session's only
     * one. Documents are read-only snapshots.
     */
    List<ThinkProcessDocument> siblings();
}
