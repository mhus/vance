package de.mhus.vance.brain.events;

import de.mhus.vance.api.thinkprocess.ProcessQueueNotification;
import de.mhus.vance.api.thinkprocess.QueuedMessageEntry;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.enginemessage.QueuedMessageEntryMapper;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingQueueChangedEvent;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fans {@link PendingQueueChangedEvent} out to the bound session's clients
 * as a {@link MessageType#PROCESS_QUEUE} notification — the live half of
 * the "queued messages" display ("active message queue", see
 * {@code planning/active-message-queue.md} §4 P3).
 *
 * <p>Two change kinds share the frame: {@code added} (the client's own
 * sends are already known via the persist-ack, this carries the
 * multi-client view) and {@code drainedIds} (the engine picked the
 * messages up at a loop boundary — the client drops its queued badge).
 *
 * <p>Optimistic side-channel by contract (same as
 * {@link ChatMessageNotificationDispatcher}): no live connection → silent
 * no-op, because the authoritative queue state is the {@code process-inbox}
 * read and survives reconnects. Never blocks the publisher — the event is
 * fired from the ack-on-persist path and the engine's drain path.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingQueueNotificationDispatcher {

    private final ThinkProcessService thinkProcessService;
    private final ClientEventPublisher events;

    @EventListener
    public void onPendingQueueChanged(PendingQueueChangedEvent event) {
        if (event.added().isEmpty() && event.drainedIds().isEmpty()) {
            return;
        }
        String sessionId = event.sessionId();
        String processName = event.processName();
        if (sessionId == null || processName == null) {
            // Drain-path events carry only the process id when the process
            // lookup was skipped — resolve once here so the notification's
            // source block is complete.
            Optional<ThinkProcessDocument> owner = thinkProcessService.findById(event.processId());
            if (owner.isEmpty()) {
                log.debug("queue-notification dropped — process gone id='{}'", event.processId());
                return;
            }
            sessionId = owner.get().getSessionId();
            processName = owner.get().getName();
        }
        ProcessQueueNotification frame = ProcessQueueNotification.builder()
                .thinkProcessId(event.processId())
                .processName(processName)
                .added(toEntries(event.added()))
                .drainedIds(event.drainedIds())
                .build();
        events.publish(sessionId, MessageType.PROCESS_QUEUE, frame);
    }

    private static List<QueuedMessageEntry> toEntries(List<EngineMessageDocument> docs) {
        return QueuedMessageEntryMapper.from(docs);
    }
}
