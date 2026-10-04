package de.mhus.vance.shared.thinkprocess;

import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Fired whenever a think-process's pending message queue changed — the
 * shared-side source for the client's "queued messages" view ("active
 * message queue", see {@code planning/active-message-queue.md} §4 P3).
 *
 * <p>Two change kinds share the event and either list may be empty:
 * {@code added} carries the freshly persisted entries (publish point is
 * the ack-on-persist in {@code ThinkProcessService.appendPending}), and
 * {@code drainedIds} carries the ids the engine just picked up (publish
 * point is {@code ThinkProcessService.drainPending}, after
 * {@code markDrained}).
 *
 * <p>The brain-side listener ({@code PendingQueueNotificationDispatcher})
 * turns this into the {@code process-queue} WS notification. Mirror of the
 * {@link ChatMessageAppendedEvent} pattern: shared publishes, brain ships.
 *
 * @param processId   target process whose queue changed
 * @param sessionId   owning session — {@code null} when the process lookup
 *                    did not resolve (drain path); the listener re-resolves
 * @param processName technical name of the process, for the notification's
 *                    source block; {@code null} like {@code sessionId}
 * @param added       entries appended to the queue since the last event
 * @param drainedIds  ids the engine has drained (picked up)
 */
public record PendingQueueChangedEvent(
        String processId,
        @Nullable String sessionId,
        @Nullable String processName,
        List<EngineMessageDocument> added,
        List<String> drainedIds) {

    public PendingQueueChangedEvent {
        added = added == null ? List.of() : List.copyOf(added);
        drainedIds = drainedIds == null ? List.of() : List.copyOf(drainedIds);
    }

    /** Convenience: event for one freshly persisted queue entry. */
    public static PendingQueueChangedEvent added(
            String processId, @Nullable String sessionId, @Nullable String processName, EngineMessageDocument entry) {
        return new PendingQueueChangedEvent(processId, sessionId, processName, List.of(entry), List.of());
    }

    /** Convenience: event for a drain of {@code messageIds}. */
    public static PendingQueueChangedEvent drained(
            String processId, @Nullable String sessionId, @Nullable String processName, List<String> messageIds) {
        return new PendingQueueChangedEvent(processId, sessionId, processName, List.of(), messageIds);
    }
}
