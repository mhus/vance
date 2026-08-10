package de.mhus.vance.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemStatus;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.inbox.ResolvedBy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Effect dispatch inside {@link InboxItemService#answer}. The hook lives
 * there — and not in the brain's WS handler — because {@code answer} is
 * the single funnel every answer path goes through; a hook further out
 * would be bypassable, which for an authorization mutation is the
 * difference between control and decoration.
 */
class InboxItemServiceEffectTest {

    private static final String TYPE = "permission-request";

    private InboxItemRepository repository;
    private MongoTemplate mongoTemplate;
    private ApplicationEventPublisher eventPublisher;
    private RecordingEffect effect;
    private InboxItemService service;

    @BeforeEach
    void setUp() {
        repository = mock(InboxItemRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        effect = new RecordingEffect();
        service = new InboxItemService(repository, mongoTemplate, eventPublisher,
                new InboxEffectRegistry(List.of(effect)));
    }

    @Test
    void answeringAnItemWithAnEffect_runsIt() {
        givenPendingThenAnswered();

        service.answer("acme", "item-1", approved(true), ResolvedBy.USER);

        assertThat(effect.calls).containsExactly("approved");
    }

    @Test
    void repeatedAnswer_runsTheEffectOnlyOnce() {
        InboxItemDocument answeredDoc = withEffect(InboxItemStatus.ANSWERED);
        when(repository.findByIdAndTenantId("item-1", "acme"))
                .thenReturn(Optional.of(answeredDoc));

        service.answer("acme", "item-1", approved(true), ResolvedBy.USER);

        // The pre-existing double-submit guard is what makes the effect
        // exactly-once — no second mechanism needed.
        assertThat(effect.calls).isEmpty();
    }

    @Test
    void lostRaceOnTheTransition_doesNotRunTheEffect() {
        when(repository.findByIdAndTenantId("item-1", "acme"))
                .thenReturn(Optional.of(withEffect(InboxItemStatus.PENDING)));
        // Someone else answered between read and update.
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(InboxItemDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        service.answer("acme", "item-1", approved(true), ResolvedBy.USER);

        assertThat(effect.calls).isEmpty();
    }

    @Test
    void effectRunsBeforeTheProcessIsNotified() {
        givenPendingThenAnswered();

        service.answer("acme", "item-1", approved(true), ResolvedBy.USER);

        // The effect is the consequence of the decision; the process
        // notification is only information about it.
        assertThat(effect.calls).containsExactly("approved");
        verify(eventPublisher).publishEvent(any(InboxItemAnsweredEvent.class));
    }

    @Test
    void failingEffect_keepsTheAnswerAndRecordsTheFailure() {
        givenPendingThenAnswered();
        effect.blowUp = true;

        Optional<InboxItemDocument> result = assertAnswerSucceeds();

        // The human decided — losing that would be worse than a failed
        // side-effect, so the item stays ANSWERED …
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(InboxItemStatus.ANSWERED);
        // … and the mismatch is traceable rather than silent.
        verify(mongoTemplate).updateFirst(any(Query.class),
                argThatPushesEffectFailure(), eq(InboxItemDocument.class));
    }

    @Test
    void failingEffect_doesNotEscapeToTheCaller() {
        givenPendingThenAnswered();
        effect.blowUp = true;

        assertThatCode(() -> service.answer("acme", "item-1", approved(true), ResolvedBy.USER))
                .doesNotThrowAnyException();
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private Optional<InboxItemDocument> assertAnswerSucceeds() {
        return service.answer("acme", "item-1", approved(true), ResolvedBy.USER);
    }

    private void givenPendingThenAnswered() {
        when(repository.findByIdAndTenantId("item-1", "acme"))
                .thenReturn(Optional.of(withEffect(InboxItemStatus.PENDING)),
                        Optional.of(withEffect(InboxItemStatus.ANSWERED)));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(InboxItemDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
    }

    private static InboxItemDocument withEffect(InboxItemStatus status) {
        return InboxItemDocument.builder()
                .id("item-1")
                .tenantId("acme")
                .assignedToUserId("alice")
                .originatorUserId("system:test")
                .type(InboxItemType.APPROVAL)
                .effectType(TYPE)
                .effectRef("req-1")
                .requiresAction(true)
                .status(status)
                .build();
    }

    private static AnswerPayload approved(boolean approved) {
        return AnswerPayload.builder()
                .outcome(AnswerOutcome.DECIDED)
                .value(Map.of("approved", approved))
                .answeredBy("alice")
                .build();
    }

    private static Update argThatPushesEffectFailure() {
        return org.mockito.ArgumentMatchers.argThat(update ->
                update != null && update.toString().contains("EFFECT_FAILED"));
    }

    private static final class RecordingEffect implements InboxEffect {
        private final List<String> calls = new ArrayList<>();
        private boolean blowUp;

        @Override
        public String effectType() {
            return TYPE;
        }

        @Override
        public void onApproved(InboxItemDocument item, AnswerPayload answer) {
            if (blowUp) {
                throw new IllegalStateException("grant storage down");
            }
            calls.add("approved");
        }

        @Override
        public void onRejected(InboxItemDocument item, AnswerPayload answer) {
            calls.add("rejected");
        }
    }
}
