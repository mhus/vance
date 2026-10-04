package de.mhus.vance.shared.thinkprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The pending-queue half of {@link ThinkProcessService} — the "active
 * message queue" surface ({@code planning/active-message-queue.md} §4).
 *
 * <p>What is pinned here: the queue-view helpers delegate to the one
 * engine-message store (no second queue), and every queue change is
 * published as a {@link PendingQueueChangedEvent} — the brain turns that
 * into the {@code process-queue} notification the clients' "queued"
 * badges live on. A change that stays silent is a badge that never
 * resolves, with no error anywhere.
 */
class ThinkProcessServiceQueueTest {

    private static final String PROCESS = "p-1";

    private ThinkProcessRepository repository;
    private EngineMessageService engineMessages;
    private ApplicationEventPublisher events;
    private ThinkProcessService service;

    @BeforeEach
    void setUp() {
        repository = mock(ThinkProcessRepository.class);
        engineMessages = mock(EngineMessageService.class);
        events = mock(ApplicationEventPublisher.class);
        service = new ThinkProcessService(repository, mock(MongoTemplate.class), events, engineMessages);
        when(repository.findById(PROCESS))
                .thenReturn(Optional.of(ThinkProcessDocument.builder()
                        .id(PROCESS)
                        .tenantId("acme")
                        .sessionId("s1")
                        .name("chat")
                        .build()));
    }

    @Test
    void appendPending_publishesTheAddedEntryWithItsMessageId() {
        PendingMessageDocument message = PendingMessageDocument.builder()
                .type(PendingMessageType.USER_CHAT_INPUT)
                .at(Instant.parse("2026-10-04T10:00:00Z"))
                .idempotencyKey("msg-1")
                .fromUser("alice")
                .content("hello")
                .build();

        boolean accepted = service.appendPending(PROCESS, message);

        assertThat(accepted).isTrue();
        ArgumentCaptor<PendingQueueChangedEvent> captor = ArgumentCaptor.forClass(PendingQueueChangedEvent.class);
        verify(events).publishEvent(captor.capture());
        PendingQueueChangedEvent event = captor.getValue();
        assertThat(event.processId()).isEqualTo(PROCESS);
        assertThat(event.sessionId()).isEqualTo("s1");
        assertThat(event.processName()).isEqualTo("chat");
        assertThat(event.drainedIds()).isEmpty();
        assertThat(event.added()).hasSize(1);
        assertThat(event.added().get(0).getMessageId())
                .as("the ack's messageId must be the queue entry's key")
                .isEqualTo("msg-1");
        assertThat(event.added().get(0).getContent()).isEqualTo("hello");
    }

    @Test
    void appendPending_unknownProcess_publishesNothing() {
        when(repository.findById(PROCESS)).thenReturn(Optional.empty());

        boolean accepted = service.appendPending(
                PROCESS,
                PendingMessageDocument.builder()
                        .type(PendingMessageType.USER_CHAT_INPUT)
                        .content("hello")
                        .build());

        assertThat(accepted).isFalse();
        verify(events, never()).publishEvent(any());
    }

    @Test
    void drainPending_publishesTheDrainedIds() {
        when(engineMessages.drainInbox(PROCESS))
                .thenReturn(List.of(EngineMessageDocument.builder()
                        .messageId("msg-1")
                        .type(PendingMessageType.USER_CHAT_INPUT)
                        .createdAt(Instant.parse("2026-10-04T10:00:00Z"))
                        .content("hello")
                        .build()));

        List<PendingMessageDocument> drained = service.drainPending(PROCESS);

        assertThat(drained).hasSize(1);
        verify(engineMessages).markDrained(List.of("msg-1"));
        ArgumentCaptor<PendingQueueChangedEvent> captor = ArgumentCaptor.forClass(PendingQueueChangedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().drainedIds()).containsExactly("msg-1");
        assertThat(captor.getValue().added()).isEmpty();
    }

    @Test
    void drainPending_emptyQueue_publishesNothing() {
        when(engineMessages.drainInbox(PROCESS)).thenReturn(List.of());

        assertThat(service.drainPending(PROCESS)).isEmpty();
        verify(events, never()).publishEvent(any());
    }

    @Test
    void queueViews_delegateToTheOneEngineMessageStore() {
        when(engineMessages.countInbox(PROCESS)).thenReturn(2L);
        when(engineMessages.drainInbox(PROCESS))
                .thenReturn(List.of(
                        EngineMessageDocument.builder()
                                .messageId("msg-1")
                                .type(PendingMessageType.USER_CHAT_INPUT)
                                .build(),
                        EngineMessageDocument.builder()
                                .messageId("msg-2")
                                .type(PendingMessageType.USER_CHAT_INPUT)
                                .build()));

        assertThat(service.hasPending(PROCESS)).isTrue();
        assertThat(service.countPending(PROCESS)).isEqualTo(2);
        List<PendingMessageDocument> queued = service.listPending(PROCESS);

        assertThat(queued).hasSize(2);
        assertThat(queued.get(0).getIdempotencyKey())
                .as("the queue read hands back the messageId as the client-facing key")
                .isEqualTo("msg-1");
    }

    @Test
    void hasPending_falseOnAnEmptyQueue() {
        when(engineMessages.countInbox(PROCESS)).thenReturn(0L);
        assertThat(service.hasPending(PROCESS)).isFalse();
    }
}
