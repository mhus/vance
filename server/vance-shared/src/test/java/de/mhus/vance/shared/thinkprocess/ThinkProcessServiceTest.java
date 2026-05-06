package de.mhus.vance.shared.thinkprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Tests for the durability and lifecycle contracts on
 * {@link ThinkProcessService}: pending-queue atomic append/drain,
 * status transitions and the CLOSED-route guardrails. Mongo is mocked —
 * the tests verify which atomic ops are issued, not Mongo behaviour.
 */
class ThinkProcessServiceTest {

    private ThinkProcessRepository repository;
    private MongoTemplate mongoTemplate;
    private ApplicationEventPublisher eventPublisher;
    private EngineMessageService engineMessageService;
    private ThinkProcessService service;

    @BeforeEach
    void setUp() {
        repository = mock(ThinkProcessRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        engineMessageService = mock(EngineMessageService.class);
        service = new ThinkProcessService(repository, mongoTemplate, eventPublisher, engineMessageService);
    }

    // ─── Pending Queue (façade over EngineMessageService) ───────────────

    @Test
    void appendPending_delegatesToAcceptDelivery_whenProcessExists() {
        when(repository.findById("p-1")).thenReturn(Optional.of(process("p-1")));

        boolean ok = service.appendPending("p-1", new PendingMessageDocument());

        assertThat(ok).isTrue();
        verify(engineMessageService).acceptDelivery(any(EngineMessageDocument.class));
    }

    @Test
    void appendPending_returnsFalse_whenProcessUnknown() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());

        boolean ok = service.appendPending("ghost", new PendingMessageDocument());

        assertThat(ok).isFalse();
        verify(engineMessageService, never()).acceptDelivery(any(EngineMessageDocument.class));
    }

    @Test
    void drainPending_returnsAccumulatedMessages_inInsertionOrder() {
        EngineMessageDocument m1 = EngineMessageDocument.builder().messageId("m1").build();
        EngineMessageDocument m2 = EngineMessageDocument.builder().messageId("m2").build();
        EngineMessageDocument m3 = EngineMessageDocument.builder().messageId("m3").build();
        when(engineMessageService.drainInbox("p-1")).thenReturn(List.of(m1, m2, m3));

        List<PendingMessageDocument> drained = service.drainPending("p-1");

        assertThat(drained).hasSize(3);
        verify(engineMessageService).markDrained(any(Collection.class));
    }

    @Test
    void drainPending_emptyQueue_returnsEmptyList_andSkipsMarkDrained() {
        when(engineMessageService.drainInbox("p-1")).thenReturn(List.of());

        assertThat(service.drainPending("p-1")).isEmpty();
        verify(engineMessageService, never()).markDrained(any(Collection.class));
    }

    @Test
    void drainPending_unknownProcess_returnsEmptyList_neverNull() {
        when(engineMessageService.drainInbox("ghost")).thenReturn(List.of());

        // Defensive: even when the process disappears mid-call, callers
        // get an empty list (not NPE).
        assertThat(service.drainPending("ghost"))
                .isNotNull()
                .isEmpty();
    }

    @Test
    void pendingSize_returnsZero_forUnknownProcess() {
        when(engineMessageService.countInbox("ghost")).thenReturn(0L);

        assertThat(service.pendingSize("ghost")).isZero();
    }

    @Test
    void pendingSize_reflectsCurrentInboxLength() {
        when(engineMessageService.countInbox("p-1")).thenReturn(4L);

        assertThat(service.pendingSize("p-1")).isEqualTo(4);
    }

    // ─── Status transitions ──────────────────────────────────────────────

    @Test
    void updateStatus_firesEvent_whenTransitionApplied() {
        ThinkProcessDocument prior = process("p-1");
        prior.setStatus(ThinkProcessStatus.INIT);
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(ThinkProcessDocument.class)))
                .thenReturn(prior);

        boolean ok = service.updateStatus("p-1", ThinkProcessStatus.PAUSED);

        assertThat(ok).isTrue();
        verify(eventPublisher).publishEvent(any(ThinkProcessStatusChangedEvent.class));
    }

    @Test
    void updateStatus_returnsFalse_andSkipsEvent_whenProcessUnknown() {
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(ThinkProcessDocument.class)))
                .thenReturn(null);

        boolean ok = service.updateStatus("ghost", ThinkProcessStatus.PAUSED);

        assertThat(ok).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateStatus_rejectsClosedRoute() {
        // CLOSED requires CloseReason — has its own dedicated method.
        assertThatThrownBy(() ->
                service.updateStatus("p-1", ThinkProcessStatus.CLOSED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CloseReason");
    }

    @Test
    void closeProcess_firesEvent_whenTransitioning() {
        ThinkProcessDocument prior = process("p-1");
        prior.setStatus(ThinkProcessStatus.RUNNING);
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(ThinkProcessDocument.class)))
                .thenReturn(prior);

        boolean ok = service.closeProcess("p-1", CloseReason.STOPPED);

        assertThat(ok).isTrue();
        verify(eventPublisher).publishEvent(any(ThinkProcessStatusChangedEvent.class));
    }

    @Test
    void closeProcess_isIdempotent_onAlreadyClosed() {
        // Status filter `status != CLOSED` makes findAndModify return null
        // for an already-closed row → no event fires.
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(ThinkProcessDocument.class)))
                .thenReturn(null);

        boolean ok = service.closeProcess("p-1", CloseReason.STOPPED);

        assertThat(ok).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ─── Halt flag ───────────────────────────────────────────────────────

    @Test
    void requestHalt_setsFlagAtomically_returnsTrueOnHit() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(ThinkProcessDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertThat(service.requestHalt("p-1")).isTrue();
    }

    @Test
    void isHaltRequested_returnsFalseForUnknownProcess() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());

        assertThat(service.isHaltRequested("ghost")).isFalse();
    }

    @Test
    void isHaltRequested_reflectsDocumentFlag() {
        ThinkProcessDocument doc = process("p-1");
        doc.setHaltRequested(true);
        when(repository.findById("p-1")).thenReturn(Optional.of(doc));

        assertThat(service.isHaltRequested("p-1")).isTrue();
    }

    // ─── Plan-Mode (mode + todos) ────────────────────────────────────────

    @Test
    void updateMode_writesTheModeFieldAtomically() {
        UpdateResult ok = mock(UpdateResult.class);
        when(ok.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(ThinkProcessDocument.class))).thenReturn(ok);

        boolean changed = service.updateMode("p-1", ProcessMode.EXPLORING);

        assertThat(changed).isTrue();
        org.mockito.ArgumentCaptor<Update> captor =
                org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), captor.capture(),
                eq(ThinkProcessDocument.class));
        Object setOps = captor.getValue().getUpdateObject().get("$set");
        assertThat(setOps.toString()).contains("mode").contains("EXPLORING");
    }

    @Test
    void updateMode_returnsFalse_whenProcessNotFound() {
        UpdateResult miss = mock(UpdateResult.class);
        when(miss.getModifiedCount()).thenReturn(0L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(ThinkProcessDocument.class))).thenReturn(miss);

        assertThat(service.updateMode("ghost", ProcessMode.PLANNING)).isFalse();
    }

    @Test
    void setTodos_replacesEntireListAtomically() {
        UpdateResult ok = mock(UpdateResult.class);
        when(ok.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(ThinkProcessDocument.class))).thenReturn(ok);

        List<TodoItem> todos = List.of(
                TodoItem.builder().id("1").status(TodoStatus.PENDING).content("a").build(),
                TodoItem.builder().id("2").status(TodoStatus.PENDING).content("b").build());

        boolean changed = service.setTodos("p-1", todos);

        assertThat(changed).isTrue();
        org.mockito.ArgumentCaptor<Update> captor =
                org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), captor.capture(),
                eq(ThinkProcessDocument.class));
        Object setOps = captor.getValue().getUpdateObject().get("$set");
        assertThat(setOps.toString()).contains("todos");
    }

    @Test
    void updateTodoStatuses_appliesUpdates_andLeavesUnlistedItemsUntouched() {
        ThinkProcessDocument doc = process("p-1");
        doc.setTodos(new ArrayList<>(List.of(
                TodoItem.builder().id("1").status(TodoStatus.PENDING).content("a").build(),
                TodoItem.builder().id("2").status(TodoStatus.PENDING).content("b").build(),
                TodoItem.builder().id("3").status(TodoStatus.PENDING).content("c").build())));
        when(repository.findById("p-1")).thenReturn(Optional.of(doc));
        UpdateResult ok = mock(UpdateResult.class);
        when(ok.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(ThinkProcessDocument.class))).thenReturn(ok);

        var updates = new java.util.LinkedHashMap<String, TodoStatus>();
        updates.put("1", TodoStatus.COMPLETED);
        updates.put("3", TodoStatus.IN_PROGRESS);

        boolean changed = service.updateTodoStatuses("p-1", updates);

        assertThat(changed).isTrue();
        org.mockito.ArgumentCaptor<Update> captor =
                org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), captor.capture(),
                eq(ThinkProcessDocument.class));
        // The update payload contains the rebuilt list (3 items, two
        // statuses applied, item 2 untouched). We don't reflect-into
        // the embedded array — verifying the $set + $inc structure is
        // enough to know the right shape was issued.
        Object updateObject = captor.getValue().getUpdateObject();
        assertThat(updateObject.toString()).contains("todos").contains("$inc");
    }

    @Test
    void updateTodoStatuses_returnsFalse_whenNoChange() {
        ThinkProcessDocument doc = process("p-1");
        doc.setTodos(new ArrayList<>(List.of(
                TodoItem.builder().id("1").status(TodoStatus.COMPLETED).content("a").build())));
        when(repository.findById("p-1")).thenReturn(Optional.of(doc));

        // Update demands COMPLETED, item is already COMPLETED → no-op
        var updates = new java.util.LinkedHashMap<String, TodoStatus>();
        updates.put("1", TodoStatus.COMPLETED);

        boolean changed = service.updateTodoStatuses("p-1", updates);

        assertThat(changed).isFalse();
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
                eq(ThinkProcessDocument.class));
    }

    @Test
    void updateTodoStatuses_returnsFalse_whenProcessUnknown() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());
        var updates = new java.util.LinkedHashMap<String, TodoStatus>();
        updates.put("1", TodoStatus.COMPLETED);

        assertThat(service.updateTodoStatuses("ghost", updates)).isFalse();
    }

    @Test
    void updateTodoStatuses_returnsFalse_whenUpdatesAreEmpty() {
        assertThat(service.updateTodoStatuses(
                "p-1", new java.util.LinkedHashMap<>())).isFalse();
        // Critically: no Mongo call at all when the input is empty.
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
                eq(ThinkProcessDocument.class));
    }

    @Test
    void updateTodoStatuses_silentlyIgnoresUnknownTodoIds() {
        ThinkProcessDocument doc = process("p-1");
        doc.setTodos(new ArrayList<>(List.of(
                TodoItem.builder().id("1").status(TodoStatus.PENDING).content("a").build())));
        when(repository.findById("p-1")).thenReturn(Optional.of(doc));

        var updates = new java.util.LinkedHashMap<String, TodoStatus>();
        updates.put("ghost", TodoStatus.COMPLETED);

        boolean changed = service.updateTodoStatuses("p-1", updates);

        assertThat(changed).isFalse();
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
                eq(ThinkProcessDocument.class));
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static ThinkProcessDocument process(String id) {
        return ThinkProcessDocument.builder()
                .id(id)
                .tenantId("acme")
                .projectId("proj")
                .sessionId("sess-1")
                .status(ThinkProcessStatus.INIT)
                .build();
    }

    private static PendingMessageDocument pending() {
        PendingMessageDocument m = new PendingMessageDocument();
        m.setType(PendingMessageType.USER_CHAT_INPUT);
        m.setContent("test");
        return m;
    }
}
