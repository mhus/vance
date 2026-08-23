package de.mhus.vance.brain.inbox;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.brain.memory.RecompactionTags;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.shared.inbox.MaximegalonAnsweredEvent;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonHistoryEntry;
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
 *
 * <p>So do items whose {@link de.mhus.vance.shared.inbox.InboxEffect}
 * reports {@code notifiesOrigin()} — <em>provided the effect actually
 * ran</em>. See {@link #originAlreadyNotified}: suppressing on the
 * declaration alone is how a process ends up waiting forever.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboxAnsweredListener {

    /**
     * History action {@code MaximegalonService.recordEffectFailure} writes
     * when the effect threw. Duplicated as a literal because the writer
     * side has no constant to share; the pairing is covered by
     * {@code InboxAnsweredListenerTest}.
     */
    private static final String ACTION_EFFECT_FAILED = "EFFECT_FAILED";

    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    private final de.mhus.vance.shared.inbox.InboxEffectRegistry effectRegistry;
    private final de.mhus.vance.shared.inbox.MaximegalonService inboxItemService;

    @EventListener
    public void onAnswered(MaximegalonAnsweredEvent event) {
        MaximegalonDocument item = event.item();
        // Recompaction-offers handle their own answers in
        // RecompactionOfferAnsweredListener (act on chat history directly,
        // no engine round-trip needed). Skip routing for them so the
        // origin process doesn't receive a generic SteerMessage it has
        // no semantics for.
        if (item.getTags() != null
                && item.getTags().contains(RecompactionTags.TAG_INBOX_OFFER)) {
            return;
        }
        if (originAlreadyNotified(item)) {
            return;
        }
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

    /**
     * Whether the item's effect has already told the origin process what
     * was decided — the one case where the generic route is redundant
     * rather than the only message the process will ever get.
     *
     * <p>{@code notifiesOrigin()} alone is not that answer: it is a static
     * property of the effect <em>type</em>, decided before anything ran.
     * {@code MaximegalonService.answer} dispatches the effect, swallows
     * whatever it throws, and publishes the answered-event regardless. A
     * listener that suppressed on the declaration therefore dropped the
     * only notification the origin was going to receive, and the process
     * stayed BLOCKED with nobody left to unblock it — no error, no
     * timeout, no way back.
     *
     * <p>So suppression asks for evidence instead. Every condition below
     * mirrors a {@code return false} in
     * {@code InboxEffectRegistry.dispatch} (nothing ran ⇒ nothing was
     * delivered) or the failure that {@code recordEffectFailure} writes
     * onto the item. When any of them says "the effect did not deliver",
     * the generic route runs — a duplicate message costs a turn, a
     * missing one costs the process.
     *
     * <p>What this cannot see is an effect that returned normally without
     * notifying (e.g. its own lookup came up empty). Closing that needs
     * {@code dispatch} to report what it did rather than what its type
     * promises.
     */
    private boolean originAlreadyNotified(MaximegalonDocument item) {
        if (!effectRegistry.notifiesOrigin(item)) {
            return false;
        }
        // Abstention is not consent: dispatch runs nothing for it.
        if (item.getAnswer() == null
                || item.getAnswer().getOutcome() != AnswerOutcome.DECIDED) {
            return false;
        }
        // Only APPROVAL carries the approve/reject answer dispatch needs.
        if (item.getType() != MaximegalonType.APPROVAL) {
            return false;
        }
        return !effectFailed(item);
    }

    /**
     * Whether the effect threw. Read from a re-load, not from the event:
     * the document the event carries was read <em>before</em> the effect
     * ran, so the failure entry is never on it.
     */
    private boolean effectFailed(MaximegalonDocument item) {
        MaximegalonDocument fresh = inboxItemService
                .findById(item.getTenantId(), item.getId())
                .orElse(item);
        if (fresh.getHistory() == null) {
            return false;
        }
        for (MaximegalonHistoryEntry entry : fresh.getHistory()) {
            if (ACTION_EFFECT_FAILED.equals(entry.getAction())) {
                log.warn("InboxAnsweredListener: effect '{}' failed on item '{}' — routing the "
                                + "generic answer so the origin process is not left waiting",
                        item.getEffectType(), item.getId());
                return true;
            }
        }
        return false;
    }
}
