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
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.api.inbox.ResolvedBy;
import java.util.LinkedHashMap;
import java.util.List;
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
 * State-machine tests for {@link MaximegalonService}. Mongo is stubbed —
 * we only verify that the service issues the right transitions, fires
 * the right events, and short-circuits when the precondition isn't met.
 */
class MaximegalonServiceTest {

    private MaximegalonRepository repository;
    private MongoTemplate mongoTemplate;
    private ApplicationEventPublisher eventPublisher;
    private MaximegalonService service;

    @BeforeEach
    void setUp() {
        repository = mock(MaximegalonRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new MaximegalonService(repository, mongoTemplate, eventPublisher,
                new InboxEffectRegistry(List.of()));
    }

    // ──── Auto-default on LOW criticality ───────────────────────────────

    @Test
    void create_lowCriticalityWithDefault_isAutoAnswered() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(MaximegalonService.PAYLOAD_DEFAULT_KEY, "yes");

        MaximegalonDocument toCreate = item("acme", "alice")
                .criticality(Criticality.LOW)
                .type(MaximegalonType.DECISION)
                .payload(payload)
                .build();
        when(repository.save(any(MaximegalonDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        MaximegalonDocument saved = service.create(toCreate);

        assertThat(saved.getStatus()).isEqualTo(MaximegalonStatus.ANSWERED);
        assertThat(saved.getResolvedBy()).isEqualTo(ResolvedBy.AUTO_DEFAULT);
        assertThat(saved.getAnswer().getOutcome()).isEqualTo(AnswerOutcome.DECIDED);
        assertThat(saved.getAnswer().getAnsweredBy()).isEqualTo("system:auto-default");

        // Both Created and Answered events fire on auto-answer.
        verify(eventPublisher).publishEvent(any(MaximegalonCreatedEvent.class));
        verify(eventPublisher).publishEvent(any(MaximegalonAnsweredEvent.class));
    }

    @Test
    void create_lowCriticalityWithoutDefault_staysPending() {
        MaximegalonDocument toCreate = item("acme", "alice")
                .criticality(Criticality.LOW)
                .type(MaximegalonType.DECISION)
                .payload(new LinkedHashMap<>()) // no default key
                .status(MaximegalonStatus.PENDING)
                .build();
        when(repository.save(any(MaximegalonDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        MaximegalonDocument saved = service.create(toCreate);

        assertThat(saved.getStatus()).isEqualTo(MaximegalonStatus.PENDING);
        verify(eventPublisher).publishEvent(any(MaximegalonCreatedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(MaximegalonAnsweredEvent.class));
    }

    @Test
    void create_higherCriticalityWithDefault_staysPending() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(MaximegalonService.PAYLOAD_DEFAULT_KEY, "yes");

        MaximegalonDocument toCreate = item("acme", "alice")
                .criticality(Criticality.NORMAL)
                .type(MaximegalonType.DECISION)
                .payload(payload)
                .status(MaximegalonStatus.PENDING)
                .build();
        when(repository.save(any(MaximegalonDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        MaximegalonDocument saved = service.create(toCreate);

        // Default-key only auto-answers at LOW.
        assertThat(saved.getStatus()).isEqualTo(MaximegalonStatus.PENDING);
        verify(eventPublisher, never()).publishEvent(any(MaximegalonAnsweredEvent.class));
    }

    // ──── answer() ──────────────────────────────────────────────────────

    @Test
    void answer_pendingItem_recordsAnswerAndFiresEvent() {
        MaximegalonDocument pending = pending("item-1", "acme", "alice");
        when(repository.findByIdAndTenantId("item-1", "acme"))
                .thenReturn(Optional.of(pending),
                        Optional.of(answered(pending, "alice")));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(MaximegalonDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        Optional<MaximegalonDocument> result = service.answer(
                "acme", "item-1",
                AnswerPayload.builder().outcome(AnswerOutcome.DECIDED)
                        .value(Map.of("v", "yes")).answeredBy("alice").build(),
                ResolvedBy.USER);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(MaximegalonStatus.ANSWERED);
        verify(eventPublisher).publishEvent(any(MaximegalonAnsweredEvent.class));
        verify(mongoTemplate, times(1)).updateFirst(any(Query.class), any(Update.class), eq(MaximegalonDocument.class));
    }

    @Test
    void answer_alreadyAnsweredItem_isIdempotent_noopOnRepeatedAnswer() {
        MaximegalonDocument already = answered(pending("item-1", "acme", "alice"), "alice");
        when(repository.findByIdAndTenantId("item-1", "acme")).thenReturn(Optional.of(already));

        Optional<MaximegalonDocument> result = service.answer(
                "acme", "item-1",
                AnswerPayload.builder().outcome(AnswerOutcome.DECIDED)
                        .answeredBy("bob").build(),
                ResolvedBy.USER);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(MaximegalonStatus.ANSWERED);
        // No update issued, no event fired.
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(MaximegalonDocument.class));
        verify(eventPublisher, never()).publishEvent(any(MaximegalonAnsweredEvent.class));
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
        MaximegalonDocument archived = pending("item-1", "acme", "alice");
        archived.setStatus(MaximegalonStatus.ARCHIVED);
        when(repository.findByIdAndTenantId("item-1", "acme")).thenReturn(Optional.of(archived));

        Optional<MaximegalonDocument> result = service.archive("acme", "item-1", "alice");

        assertThat(result).isPresent();
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(MaximegalonDocument.class));
        verify(eventPublisher, never()).publishEvent(any(MaximegalonArchivedEvent.class));
    }

    @Test
    void unarchive_restoresToAnswered_whenAnswerOnFile() {
        MaximegalonDocument archivedWithAnswer = answered(pending("item-1", "acme", "alice"), "alice");
        archivedWithAnswer.setStatus(MaximegalonStatus.ARCHIVED);
        MaximegalonDocument restored = answered(pending("item-1", "acme", "alice"), "alice");
        restored.setStatus(MaximegalonStatus.ANSWERED);

        when(repository.findByIdAndTenantId("item-1", "acme"))
                .thenReturn(Optional.of(archivedWithAnswer), Optional.of(restored));

        Optional<MaximegalonDocument> result = service.unarchive("acme", "item-1", "alice");

        assertThat(result).isPresent();
        ArgumentCaptor<Update> capt = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), capt.capture(), eq(MaximegalonDocument.class));
        assertThat(capt.getValue().toString()).contains("ANSWERED");
    }

    @Test
    void unarchive_restoresToPending_whenNoAnswer() {
        MaximegalonDocument archivedNoAnswer = pending("item-1", "acme", "alice");
        archivedNoAnswer.setStatus(MaximegalonStatus.ARCHIVED);
        archivedNoAnswer.setAnswer(null);
        MaximegalonDocument restored = pending("item-1", "acme", "alice");

        when(repository.findByIdAndTenantId("item-1", "acme"))
                .thenReturn(Optional.of(archivedNoAnswer), Optional.of(restored));

        Optional<MaximegalonDocument> result = service.unarchive("acme", "item-1", "alice");

        assertThat(result).isPresent();
        ArgumentCaptor<Update> capt = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), capt.capture(), eq(MaximegalonDocument.class));
        assertThat(capt.getValue().toString()).contains("PENDING");
    }

    @Test
    void unarchive_nonArchivedItem_isNoop() {
        MaximegalonDocument pending = pending("item-1", "acme", "alice");
        when(repository.findByIdAndTenantId("item-1", "acme")).thenReturn(Optional.of(pending));

        Optional<MaximegalonDocument> result = service.unarchive("acme", "item-1", "alice");

        assertThat(result).isPresent();
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(MaximegalonDocument.class));
    }

    // ──── delegate() ────────────────────────────────────────────────────

    @Test
    void delegate_toSameUser_isNoop() {
        MaximegalonDocument doc = pending("item-1", "acme", "alice");
        when(repository.findByIdAndTenantId("item-1", "acme")).thenReturn(Optional.of(doc));

        Optional<MaximegalonDocument> result = service.delegate(
                "acme", "item-1", "alice", "alice", null);

        assertThat(result).isPresent();
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(MaximegalonDocument.class));
        verify(eventPublisher, never()).publishEvent(any(MaximegalonDelegatedEvent.class));
    }

    @Test
    void delegate_toDifferentUser_recordsTransitionAndFiresEvent() {
        MaximegalonDocument doc = pending("item-1", "acme", "alice");
        MaximegalonDocument afterDelegate = pending("item-1", "acme", "bob");
        when(repository.findByIdAndTenantId("item-1", "acme"))
                .thenReturn(Optional.of(doc), Optional.of(afterDelegate));

        Optional<MaximegalonDocument> result = service.delegate(
                "acme", "item-1", "bob", "alice", "fyi");

        assertThat(result).isPresent();
        assertThat(result.get().getAssignedToUserId()).isEqualTo("bob");
        verify(eventPublisher).publishEvent(any(MaximegalonDelegatedEvent.class));
    }

    // ──── countPending() ────────────────────────────────────────────────

    @Test
    void countPending_countsPendingAndActionSubset_withoutLoadingDocuments() {
        when(mongoTemplate.count(any(Query.class), eq(MaximegalonDocument.class)))
                .thenReturn(7L, 3L);

        MaximegalonService.PendingCounts counts =
                service.countPending("acme", List.of("alice"));

        assertThat(counts.total()).isEqualTo(7L);
        assertThat(counts.requiresAction()).isEqualTo(3L);
        // Counting only — the badge never needs a body.
        verify(mongoTemplate, never()).find(any(Query.class), eq(MaximegalonDocument.class));

        ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, times(2)).count(queries.capture(), eq(MaximegalonDocument.class));
        String all = queries.getAllValues().get(0).toString();
        String actionable = queries.getAllValues().get(1).toString();
        assertThat(all).contains("acme").contains("alice").contains("PENDING");
        assertThat(all).doesNotContain("requiresAction");
        // Second query is the same filter plus the requiresAction clause —
        // proves the shared criteria builder isn't leaking state.
        assertThat(actionable).contains("PENDING").contains("requiresAction");
    }

    // ──── helpers ───────────────────────────────────────────────────────

    // ──── Threads about one document ────────────────────────────────────

    @Test
    void listByDocument_appliesTheCallersCeiling() {
        // How many threads a document collects is written by whatever automation
        // posts them, so this read must not be a promise about somebody else's
        // behaviour. The caller asks for one more than it shows and learns from
        // the extra row that it is looking at a window.
        when(mongoTemplate.find(any(Query.class), eq(MaximegalonDocument.class)))
                .thenReturn(List.of());
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);

        service.listByDocument("acme", "doc-1", 41);

        verify(mongoTemplate).find(query.capture(), eq(MaximegalonDocument.class));
        assertThat(query.getValue().getLimit()).isEqualTo(41);
        assertThat(query.getValue().getQueryObject().toJson())
                .contains("documentRef.documentId").contains("doc-1");
        // Messages stay behind: a discussion list wants titles.
        assertThat(query.getValue().getFieldsObject().toJson()).contains("messages");
    }

    private static MaximegalonDocument.MaximegalonDocumentBuilder item(String tenant, String assignee) {
        return MaximegalonDocument.builder()
                .tenantId(tenant)
                .assignedToUserId(assignee)
                .originatorUserId("system:test")
                .requiresAction(true) // gate for auto-answer; ASK-style item
                .status(MaximegalonStatus.PENDING);
    }

    private static MaximegalonDocument pending(String id, String tenant, String assignee) {
        return item(tenant, assignee).id(id).build();
    }

    private static MaximegalonDocument answered(MaximegalonDocument base, String by) {
        MaximegalonDocument copy = MaximegalonDocument.builder()
                .id(base.getId())
                .tenantId(base.getTenantId())
                .assignedToUserId(base.getAssignedToUserId())
                .originatorUserId(base.getOriginatorUserId())
                .status(MaximegalonStatus.ANSWERED)
                .answer(AnswerPayload.builder()
                        .outcome(AnswerOutcome.DECIDED)
                        .answeredBy(by)
                        .build())
                .resolvedBy(ResolvedBy.USER)
                .build();
        return copy;
    }
}
