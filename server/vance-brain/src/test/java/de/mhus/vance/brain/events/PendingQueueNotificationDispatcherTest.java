package de.mhus.vance.brain.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessQueueNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.PendingQueueChangedEvent;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The {@code process-queue} fan-out behind the clients' "queued messages"
 * display ({@code planning/active-message-queue.md} §4 P3).
 *
 * <p>Pinned: the notification reaches the owning session's clients with
 * the added entries and/or the drained ids — and a queue change whose
 * event carries neither list is not worth a frame.
 */
class PendingQueueNotificationDispatcherTest {

    private static final String PROCESS = "p-1";

    private ThinkProcessService thinkProcessService;
    private ClientEventPublisher events;
    private PendingQueueNotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        events = mock(ClientEventPublisher.class);
        dispatcher = new PendingQueueNotificationDispatcher(thinkProcessService, events);
    }

    @Test
    void addedEntry_isPublishedToTheOwningSession() {
        EngineMessageDocument entry = EngineMessageDocument.builder()
                .messageId("msg-1")
                .tenantId("acme")
                .targetProcessId(PROCESS)
                .createdAt(Instant.parse("2026-10-04T10:00:00Z"))
                .type(PendingMessageType.USER_CHAT_INPUT)
                .fromUser("alice")
                .fromUserDisplayName("Alice")
                .content("hello")
                .build();

        dispatcher.onPendingQueueChanged(PendingQueueChangedEvent.added(PROCESS, "s1", "chat", entry));

        ArgumentCaptor<ProcessQueueNotification> captor = frameCaptor();
        verify(events).publish(eq("s1"), eq(MessageType.PROCESS_QUEUE), captor.capture());
        ProcessQueueNotification frame = captor.getValue();
        assertThat(frame.getThinkProcessId()).isEqualTo(PROCESS);
        assertThat(frame.getProcessName()).isEqualTo("chat");
        assertThat(frame.getDrainedIds()).isEmpty();
        assertThat(frame.getAdded()).hasSize(1);
        assertThat(frame.getAdded().get(0).getMessageId()).isEqualTo("msg-1");
        assertThat(frame.getAdded().get(0).getSenderUserId()).isEqualTo("alice");
        assertThat(frame.getAdded().get(0).getSenderDisplayName()).isEqualTo("Alice");
        assertThat(frame.getAdded().get(0).getContent()).isEqualTo("hello");
    }

    @Test
    void drainedIds_arePublishedToTheOwningSession() {
        dispatcher.onPendingQueueChanged(
                PendingQueueChangedEvent.drained(PROCESS, "s1", "chat", List.of("msg-1", "msg-2")));

        ArgumentCaptor<ProcessQueueNotification> captor = frameCaptor();
        verify(events).publish(eq("s1"), eq(MessageType.PROCESS_QUEUE), captor.capture());
        ProcessQueueNotification frame = captor.getValue();
        assertThat(frame.getDrainedIds()).containsExactly("msg-1", "msg-2");
        assertThat(frame.getAdded()).isEmpty();
    }

    @Test
    void eventWithoutSession_resolvesTheOwningProcess() {
        when(thinkProcessService.findById(PROCESS))
                .thenReturn(Optional.of(ThinkProcessDocument.builder()
                        .id(PROCESS)
                        .sessionId("s9")
                        .name("hub")
                        .build()));

        dispatcher.onPendingQueueChanged(PendingQueueChangedEvent.drained(PROCESS, null, null, List.of("msg-1")));

        ArgumentCaptor<ProcessQueueNotification> captor = frameCaptor();
        verify(events).publish(eq("s9"), eq(MessageType.PROCESS_QUEUE), captor.capture());
        assertThat(captor.getValue().getProcessName()).isEqualTo("hub");
    }

    @Test
    void processGone_dropsTheFrame() {
        when(thinkProcessService.findById(PROCESS)).thenReturn(Optional.empty());

        dispatcher.onPendingQueueChanged(PendingQueueChangedEvent.drained(PROCESS, null, null, List.of("msg-1")));

        verify(events, never()).publish(any(), any(), any());
    }

    @Test
    void emptyChange_publishesNothing() {
        dispatcher.onPendingQueueChanged(new PendingQueueChangedEvent(PROCESS, "s1", "chat", List.of(), List.of()));

        verify(events, never()).publish(any(), any(), any());
    }

    private static ArgumentCaptor<ProcessQueueNotification> frameCaptor() {
        return ArgumentCaptor.forClass(ProcessQueueNotification.class);
    }
}
