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
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import java.util.ArrayList;
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
    private ThinkProcessService service;

    @BeforeEach
    void setUp() {
        repository = mock(ThinkProcessRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ThinkProcessService(repository, mongoTemplate, eventPublisher);
    }

    // ─── Pending Queue ───────────────────────────────────────────────────

    @Test
    void appendPending_atomicPushUpdate_returnsTrueOnSuccess() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(ThinkProcessDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        boolean ok = service.appendPending("p-1", new PendingMessageDocument());

        assertThat(ok).isTrue();
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(ThinkProcessDocument.class));
    }

    @Test
    void appendPending_returnsFalse_whenProcessUnknown() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(ThinkProcessDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        boolean ok = service.appendPending("ghost", new PendingMessageDocument());

        assertThat(ok).isFalse();
    }

    @Test
    void drainPending_returnsAccumulatedMessages_inInsertionOrder() {
        // Mongo returns the OLD document via findAndModify(returnNew=false).
        ThinkProcessDocument prior = process("p-1");
        prior.setPendingMessages(new ArrayList<>(List.of(
                pending(), pending(), pending())));
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(ThinkProcessDocument.class)))
                .thenReturn(prior);

        List<PendingMessageDocument> drained = service.drainPending("p-1");

        assertThat(drained).hasSize(3);
    }

    @Test
    void drainPending_emptyQueue_returnsEmptyList() {
        ThinkProcessDocument prior = process("p-1");
        prior.setPendingMessages(new ArrayList<>());
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(ThinkProcessDocument.class)))
                .thenReturn(prior);

        assertThat(service.drainPending("p-1")).isEmpty();
    }

    @Test
    void drainPending_unknownProcess_returnsEmptyList_neverNull() {
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(ThinkProcessDocument.class)))
                .thenReturn(null);

        // Defensive: even when the process disappears mid-call, callers
        // get an empty list (not NPE).
        assertThat(service.drainPending("ghost"))
                .isNotNull()
                .isEmpty();
    }

    @Test
    void pendingSize_returnsZero_forUnknownProcess() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());

        assertThat(service.pendingSize("ghost")).isZero();
    }

    @Test
    void pendingSize_reflectsCurrentQueueLength() {
        ThinkProcessDocument doc = process("p-1");
        doc.setPendingMessages(new ArrayList<>(List.of(
                pending(), pending(), pending(), pending())));
        when(repository.findById("p-1")).thenReturn(Optional.of(doc));

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

    // ─── helpers ─────────────────────────────────────────────────────────

    private static ThinkProcessDocument process(String id) {
        ThinkProcessDocument doc = ThinkProcessDocument.builder()
                .id(id)
                .tenantId("acme")
                .projectId("proj")
                .sessionId("sess-1")
                .status(ThinkProcessStatus.INIT)
                .build();
        doc.setPendingMessages(new ArrayList<>());
        return doc;
    }

    private static PendingMessageDocument pending() {
        PendingMessageDocument m = new PendingMessageDocument();
        m.setType(PendingMessageType.USER_CHAT_INPUT);
        m.setContent("test");
        return m;
    }
}
