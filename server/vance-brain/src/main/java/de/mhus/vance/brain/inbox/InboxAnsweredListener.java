package de.mhus.vance.brain.inbox;

import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.shared.inbox.InboxItemAnsweredEvent;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Routes inbox answers back to the originating process.
 *
 * <p>When an item is answered (by a user, by AUTO_DEFAULT at
 * create-time, or — v2 — by an auto-resolver worker), this
 * listener:
 *
 * <ol>
 *   <li>Builds a {@link SteerMessage.InboxAnswer} carrying the item
 *       id, type, and the {@code AnswerPayload}.</li>
 *   <li>Encodes it via {@link SteerMessageCodec} into a
 *       {@link PendingMessageDocument}.</li>
 *   <li>Appends to the originating process's pending queue (atomic
 *       Mongo {@code $push}) and triggers a lane-turn through
 *       {@link ProcessEventEmitter#scheduleTurn}.</li>
 * </ol>
 *
 * <p>Items without an {@code originProcessId} (pure tool-driven
 * outputs, or items where the originating process has gone away)
 * skip the routing — the answer stays on the item alone for audit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboxAnsweredListener {

    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;

    @EventListener
    public void onAnswered(InboxItemAnsweredEvent event) {
        InboxItemDocument item = event.item();
        String processId = item.getOriginProcessId();
        if (processId == null || processId.isBlank()) {
            return; // not waiting on a process
        }
        if (item.getAnswer() == null) {
            log.warn("InboxAnsweredListener: item id='{}' answered with null payload — skipping route",
                    item.getId());
            return;
        }
        SteerMessage.InboxAnswer steer = new SteerMessage.InboxAnswer(
                Instant.now(),
                /*idempotencyKey*/ item.getId(),
                item.getId(),
                item.getType(),
                item.getAnswer());
        PendingMessageDocument doc = SteerMessageCodec.toDocument(steer);
        boolean appended = thinkProcessService.appendPending(processId, doc);
        if (!appended) {
            log.warn("InboxAnsweredListener: target process gone id='{}' (item id='{}')",
                    processId, item.getId());
            return;
        }
        eventEmitter.scheduleTurn(processId);
        log.info("InboxAnsweredListener: routed answer item='{}' → process='{}' outcome={}",
                item.getId(), processId, item.getAnswer().getOutcome());
    }
}
