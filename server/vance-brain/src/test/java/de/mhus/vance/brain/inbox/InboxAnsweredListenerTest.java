package de.mhus.vance.brain.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.brain.memory.RecompactionTags;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.inbox.InboxEffectRegistry;
import de.mhus.vance.shared.inbox.MaximegalonAnsweredEvent;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonHistoryEntry;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The generic answer route is the default and must stay that way; the
 * exceptions are items whose answer is already delivered by someone who
 * can say it better.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InboxAnsweredListenerTest {

    private static final String PROCESS = "proc-1";

    @Mock
    ThinkProcessService thinkProcessService;
    @Mock
    ProcessEventEmitter eventEmitter;
    @Mock
    InboxEffectRegistry effectRegistry;
    @Mock
    de.mhus.vance.shared.inbox.MaximegalonService inboxItemService;

    @Test
    void anOrdinaryAnswer_reachesTheOriginProcess() {
        when(thinkProcessService.appendPending(anyString(), any())).thenReturn(true);

        listener().onAnswered(new MaximegalonAnsweredEvent(item()));

        verify(thinkProcessService).appendPending(eq(PROCESS), any(PendingMessageDocument.class));
        verify(eventEmitter).scheduleTurn(PROCESS);
    }

    @Test
    void anEffectThatNotifiesItself_suppressesTheGenericRoute() {
        // Observed live on a permission request: the effect pushed
        // "access granted, you can proceed" and this listener added a
        // generic InboxAnswer steer, so one decision arrived twice and
        // both were drained into the same turn.
        MaximegalonDocument item = item();
        when(effectRegistry.notifiesOrigin(item)).thenReturn(true);

        listener().onAnswered(new MaximegalonAnsweredEvent(item));

        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void anUnknownEffect_keepsTheGenericRoute() {
        // The registry answers false for effect types it does not know.
        // Staying audible is the safe default: a lost answer strands the
        // process, a duplicate one only costs tokens.
        when(thinkProcessService.appendPending(anyString(), any())).thenReturn(true);
        when(effectRegistry.notifiesOrigin(any())).thenReturn(false);

        listener().onAnswered(new MaximegalonAnsweredEvent(item()));

        verify(eventEmitter).scheduleTurn(PROCESS);
    }

    @Test
    void aRecompactionOffer_isStillHandledElsewhere() {
        MaximegalonDocument item = item();
        item.setTags(List.of(RecompactionTags.TAG_INBOX_OFFER));

        listener().onAnswered(new MaximegalonAnsweredEvent(item));

        verify(thinkProcessService, never()).appendPending(anyString(), any());
    }

    @Test
    void anItemWithoutAnOrigin_isNotRouted() {
        MaximegalonDocument item = item();
        item.setOriginProcessId(null);

        listener().onAnswered(new MaximegalonAnsweredEvent(item));

        verify(thinkProcessService, never()).appendPending(anyString(), any());
    }

    @Test
    void anEffectThatFailed_stillReachesTheOriginProcess() {
        // MaximegalonService.answer swallows whatever the effect throws and
        // publishes the answered-event anyway. Suppressing on the effect
        // *type* therefore dropped the only message the origin was ever
        // going to get: the item is ANSWERED, the mutation never happened,
        // and the process stays BLOCKED with nobody left to unblock it.
        MaximegalonDocument item = item();
        when(effectRegistry.notifiesOrigin(item)).thenReturn(true);
        when(thinkProcessService.appendPending(anyString(), any())).thenReturn(true);
        MaximegalonDocument afterFailure = item();
        afterFailure.setHistory(List.of(
                MaximegalonHistoryEntry.builder().action("ANSWERED").build(),
                MaximegalonHistoryEntry.builder().action("EFFECT_FAILED").build()));
        when(inboxItemService.findById(any(), eq("item-1")))
                .thenReturn(java.util.Optional.of(afterFailure));

        listener().onAnswered(new MaximegalonAnsweredEvent(item));

        verify(thinkProcessService).appendPending(eq(PROCESS), any(PendingMessageDocument.class));
        verify(eventEmitter).scheduleTurn(PROCESS);
    }

    @Test
    void anAbstention_keepsTheGenericRoute_evenForANotifyingEffect() {
        // Abstention is not consent, so InboxEffectRegistry.dispatch runs
        // nothing at all — and an effect that did not run notified nobody.
        MaximegalonDocument item = item();
        item.getAnswer().setOutcome(AnswerOutcome.UNDECIDABLE);
        when(effectRegistry.notifiesOrigin(item)).thenReturn(true);
        when(thinkProcessService.appendPending(anyString(), any())).thenReturn(true);

        listener().onAnswered(new MaximegalonAnsweredEvent(item));

        verify(eventEmitter).scheduleTurn(PROCESS);
    }

    @Test
    void aNonApprovalItem_keepsTheGenericRoute_evenForANotifyingEffect() {
        // Only APPROVAL carries the approve/reject answer dispatch needs;
        // anything else is refused there and nothing is delivered.
        MaximegalonDocument item = item();
        item.setType(de.mhus.vance.api.inbox.MaximegalonType.DECISION);
        when(effectRegistry.notifiesOrigin(item)).thenReturn(true);
        when(thinkProcessService.appendPending(anyString(), any())).thenReturn(true);

        listener().onAnswered(new MaximegalonAnsweredEvent(item));

        verify(eventEmitter).scheduleTurn(PROCESS);
    }

    private InboxAnsweredListener listener() {
        return new InboxAnsweredListener(
                thinkProcessService, eventEmitter, effectRegistry, inboxItemService);
    }

    private static MaximegalonDocument item() {
        MaximegalonDocument item = new MaximegalonDocument();
        item.setId("item-1");
        item.setTenantId("acme");
        item.setOriginProcessId(PROCESS);
        item.setType(de.mhus.vance.api.inbox.MaximegalonType.APPROVAL);
        AnswerPayload answer = new AnswerPayload();
        answer.setOutcome(AnswerOutcome.DECIDED);
        item.setAnswer(answer);
        return item;
    }
}
