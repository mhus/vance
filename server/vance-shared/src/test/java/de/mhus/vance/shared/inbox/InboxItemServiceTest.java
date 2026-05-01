package de.mhus.vance.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemStatus;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.inbox.ResolvedBy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * State-machine tests for {@link InboxItemService}. Mongo is stubbed —
 * we only verify that the service issues the right transitions, fires
 * the right events, and short-circuits when the precondition isn't met.
 */
class InboxItemServiceTest {

    private InboxItemRepository repository;
    private MongoTemplate mongoTemplate;
    private ApplicationEventPublisher eventPublisher;
    private InboxItemService service;

    @BeforeEach
    void setUp() {
        repository = mock(InboxItemRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new InboxItemService(repository, mongoTemplate, eventPublisher);
    }

    // ──── Auto-default on LOW criticality ───────────────────────────────

    @Test
    void create_lowCriticalityWithDefault_isAutoAnswered() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(InboxItemService.PAYLOAD_DEFAULT_KEY, "yes");

        InboxItemDocument toCreate = item("acme", "alice")
                .criticality(Criticality.LOW)
                .type(InboxItemType.DECISION)
                .payload(payload)
                .build();
        when(repository.save(any(InboxItemDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        InboxItemDocument saved = service.create(toCreate);

        assertThat(saved.getStatus()).isEqualTo(InboxItemStatus.ANSWERED);
        assertThat(saved.getResolvedBy()).isEqualTo(ResolvedBy.AUTO_DEFAULT);
        assertThat(saved.getAnswer().getOutcome()).isEqualTo(AnswerOutcome.DECIDED);
        assertThat(saved.getAnswer().getAnsweredBy()).isEqualTo("system:auto-default");

        // Both Created and Answered events fire on auto-answer.
        verify(eventPublisher).publishEvent(any(InboxItemCreatedEvent.class));
        verify(eventPublisher).publishEvent(any(InboxItemAnsweredEvent.class));
    }

    @Test
    void create_lowCriticalityWithoutDefault_staysPending() {
        InboxItemDocument toCreate = item("acme", "alice")
                .criticality(Criticality.LOW)
                .type(InboxItemType.DECISION)
                .payload(new LinkedHashMap<>()) // no default key
                .status(InboxItemStatus.PENDING)
                .build();
        when(repository.save(any(InboxItemDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        InboxItemDocument saved = service.create(toCreate);

        assertThat(saved.getStatus()).isEqualTo(InboxItemStatus.PENDING);
        verify(eventPublisher).publishEvent(any(InboxItemCreatedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(InboxItemAnsweredEvent.class));
    }

    @Test
    void create_higherCriticalityWithDefault_staysPending() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(InboxItemService.PAYLOAD_DEFAULT_KEY, "yes");

        InboxItemDocument toCreate = item("acme", "alice")
                .criticality(Criticality.NORMAL)
                .type(InboxItemType.DECISION)
                .payload(payload)
                .status(InboxItemStatus.PENDING)
                .build();
        when(repository.save(any(InboxItemDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        InboxItemDocument saved = service.create(toCreate);

        // Default-key only auto-answers at LOW.
        assertThat(saved.getStatus()).isEqualTo(InboxItemStatus.PENDING);
        verify(eventPublisher, never()).publishEvent(any(InboxItemAnsweredEvent.class));
    }

    // ──── answer() ──────────────────────────────────────────────────────

    @Test
    void answer_pendingItem_recordsAnswerAndFiresEvent() {
        InboxItemDocument pending = pending("item-1", "acme", "alice");
        when(repository.findByIdAndTenantId("item-1", "acme"))
                .thenReturn(Optional.of(pending),
                        Optional.of(answered(pending, "alice")));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(InboxItemDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        Optional<InboxItemDocument> result = service.answer(
                "acme", "item-1",
                AnswerPayload.builder().outcome(AnswerOutcome.DECIDED)
                        .value(Map.of("v", "yes")).answeredBy("alice").build(),
                ResolvedBy.USER);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(InboxItemStatus.ANSWERED);
        verify(eventPublisher).publishEvent(any(InboxItemAnsweredEvent.class));
        verify(mongoTemplate, times(1)).updateFirst(any(Query.class), any(Update.class), eq(InboxItemDocument.class));
    }

    @Test
    void answer_alreadyAnsweredItem_isIdempotent_noopOnRepeatedAnswer() {
        InboxItemDocument already = answered(pending("item-1", "acme", "alice"), "alice");
        when(repository.findByIdAndTenantId("item-1", "acme")).thenReturn(Optional.of(already));

        Optional<InboxItemDocument> result = service.answer(
                "acme", "item-1",
                AnswerPayload.builder().outcome(AnswerOutcome.DECIDED)
                        .answeredBy("bob").build(),
                ResolvedBy.USER);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(InboxItemStatus.ANSWERED);
        // No update issued, no event fired.
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(InboxItemDocument.class));
        verify(eventPublisher, never()).publishEvent(any(InboxItemAnsweredEvent.class));
    }

    @Test
    void answer_unknownItem_returnsEmpty() {
        when(repository.findByIdAndTenantId("ghost", "acme")).thenReturn(Optional.empty());

        assertThat(service.answer("acme", "ghost",
                AnswerPayload.builder().outcome(AnswerOutcome.DECIDED).answeredBy("x").build(),
                ResolvedBy.USER)).isEmpty();
    }

    // ──── archive() / unarchive() ───────────────────────────────────────

    @Test
    void archive_alreadyArchivedItem_isNoop() {
        InboxItemDocument archived = pending("item-1", "acme", "alice");
        archived.setStatus(InboxItemStatus.ARCHIVED);
        when(repository.findByIdAndTenantId("item-1", "acme")).thenReturn(Optional.of(archived));

        Optional<InboxItemDocument> result = service.archive("acme", "item-1", "alice");

        assertThat(result).isPresent();
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(InboxItemDocument.class));
        verify(eventPublisher, never()).publishEvent(any(InboxItemArchivedEvent.class));
    }

    @Test
    void unarchive_restoresToAnswered_whenAnswerOnFile() {
        InboxItemDocument archivedWithAnswer = answered(pending("item-1", "acme", "alice"), "alice");
        archivedWithAnswer.setStatus(InboxItemStatus.ARCHIVED);
        InboxItemDocument restored = answered(pending("item-1", "acme", "alice"), "alice");
        restored.setStatus(InboxItemStatus.ANSWERED);

        when(repository.findByIdAndTenantId("item-1", "acme"))
                .thenReturn(Optional.of(archivedWithAnswer), Optional.of(restored));

        Optional<InboxItemDocument> result = service.unarchive("acme", "item-1", "alice");

        assertThat(result).isPresent();
        ArgumentCaptor<Update> capt = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), capt.capture(), eq(InboxItemDocument.class));
        assertThat(capt.getValue().toString()).contains("ANSWERED");
    }

    @Test
    void unarchive_restoresToPending_whenNoAnswer() {
        InboxItemDocument archivedNoAnswer = pending("item-1", "acme", "alice");
        archivedNoAnswer.setStatus(InboxItemStatus.ARCHIVED);
        archivedNoAnswer.setAnswer(null);
        InboxItemDocument restored = pending("item-1", "acme", "alice");

        when(repository.findByIdAndTenantId("item-1", "acme"))
                .thenReturn(Optional.of(archivedNoAnswer), Optional.of(restored));

        Optional<InboxItemDocument> result = service.unarchive("acme", "item-1", "alice");

        assertThat(result).isPresent();
        ArgumentCaptor<Update> capt = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), capt.capture(), eq(InboxItemDocument.class));
        assertThat(capt.getValue().toString()).contains("PENDING");
    }

    @Test
    void unarchive_nonArchivedItem_isNoop() {
        InboxItemDocument pending = pending("item-1", "acme", "alice");
        when(repository.findByIdAndTenantId("item-1", "acme")).thenReturn(Optional.of(pending));

        Optional<InboxItemDocument> result = service.unarchive("acme", "item-1", "alice");

        assertThat(result).isPresent();
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(InboxItemDocument.class));
    }

    // ──── delegate() ────────────────────────────────────────────────────

    @Test
    void delegate_toSameUser_isNoop() {
        InboxItemDocument doc = pending("item-1", "acme", "alice");
        when(repository.findByIdAndTenantId("item-1", "acme")).thenReturn(Optional.of(doc));

        Optional<InboxItemDocument> result = service.delegate(
                "acme", "item-1", "alice", "alice", null);

        assertThat(result).isPresent();
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(InboxItemDocument.class));
        verify(eventPublisher, never()).publishEvent(any(InboxItemDelegatedEvent.class));
    }

    @Test
    void delegate_toDifferentUser_recordsTransitionAndFiresEvent() {
        InboxItemDocument doc = pending("item-1", "acme", "alice");
        InboxItemDocument afterDelegate = pending("item-1", "acme", "bob");
        when(repository.findByIdAndTenantId("item-1", "acme"))
                .thenReturn(Optional.of(doc), Optional.of(afterDelegate));

        Optional<InboxItemDocument> result = service.delegate(
                "acme", "item-1", "bob", "alice", "fyi");

        assertThat(result).isPresent();
        assertThat(result.get().getAssignedToUserId()).isEqualTo("bob");
        verify(eventPublisher).publishEvent(any(InboxItemDelegatedEvent.class));
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private static InboxItemDocument.InboxItemDocumentBuilder item(String tenant, String assignee) {
        return InboxItemDocument.builder()
                .tenantId(tenant)
                .assignedToUserId(assignee)
                .originatorUserId("system:test")
                .requiresAction(true) // gate for auto-answer; ASK-style item
                .status(InboxItemStatus.PENDING);
    }

    private static InboxItemDocument pending(String id, String tenant, String assignee) {
        return item(tenant, assignee).id(id).build();
    }

    private static InboxItemDocument answered(InboxItemDocument base, String by) {
        InboxItemDocument copy = InboxItemDocument.builder()
                .id(base.getId())
                .tenantId(base.getTenantId())
                .assignedToUserId(base.getAssignedToUserId())
                .originatorUserId(base.getOriginatorUserId())
                .status(InboxItemStatus.ANSWERED)
                .answer(AnswerPayload.builder()
                        .outcome(AnswerOutcome.DECIDED)
                        .answeredBy(by)
                        .build())
                .resolvedBy(ResolvedBy.USER)
                .build();
        return copy;
    }
}
