package de.mhus.vance.brain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.notification.NotificationDto;
import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NotificationService} — verifies envelope shape
 * (source block, severity default), routing key, and the
 * no-session-→-drop contract. No Spring context, no real WebSocket.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String SESSION_ID = "sess-42";
    private static final String PROCESS_ID = "proc-1";
    private static final String PROCESS_NAME = "worker-1";
    private static final String PROCESS_TITLE = "Batch importer";

    @Mock ClientEventPublisher events;
    NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(events);
    }

    @Test
    void publish_routesToSessionId_withSourceBlock() {
        ThinkProcessDocument process = process(SESSION_ID, PROCESS_ID, PROCESS_NAME, PROCESS_TITLE);
        when(events.publish(eq(SESSION_ID), eq(MessageType.NOTIFY), any())).thenReturn(true);

        boolean delivered = service.publish(process, "Batch done — 47 rows", NotificationSeverity.INFO);

        assertThat(delivered).isTrue();
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(SESSION_ID), eq(MessageType.NOTIFY), captor.capture());
        NotificationDto dto = (NotificationDto) captor.getValue();
        assertThat(dto.getText()).isEqualTo("Batch done — 47 rows");
        assertThat(dto.getSeverity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(dto.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(dto.getSourceProcessId()).isEqualTo(PROCESS_ID);
        assertThat(dto.getSourceProcessName()).isEqualTo(PROCESS_NAME);
        assertThat(dto.getSourceProcessTitle()).isEqualTo(PROCESS_TITLE);
        assertThat(dto.getEmittedAt()).isNotNull();
    }

    @Test
    void publish_defaultsSeverityToInfo_whenNull() {
        ThinkProcessDocument process = process(SESSION_ID, PROCESS_ID, PROCESS_NAME, null);
        when(events.publish(eq(SESSION_ID), eq(MessageType.NOTIFY), any())).thenReturn(true);

        service.publish(process, "fertig", null);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(SESSION_ID), eq(MessageType.NOTIFY), captor.capture());
        NotificationDto dto = (NotificationDto) captor.getValue();
        assertThat(dto.getSeverity()).isEqualTo(NotificationSeverity.INFO);
    }

    @Test
    void publish_propagatesEachSeverity() {
        ThinkProcessDocument process = process(SESSION_ID, PROCESS_ID, PROCESS_NAME, null);
        when(events.publish(eq(SESSION_ID), eq(MessageType.NOTIFY), any())).thenReturn(true);

        for (NotificationSeverity sev : NotificationSeverity.values()) {
            service.publish(process, "x", sev);
        }

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.times(3))
                .publish(eq(SESSION_ID), eq(MessageType.NOTIFY), captor.capture());
        assertThat(captor.getAllValues())
                .extracting(o -> ((NotificationDto) o).getSeverity())
                .containsExactly(
                        NotificationSeverity.INFO,
                        NotificationSeverity.WARN,
                        NotificationSeverity.ERROR);
    }

    @Test
    void publish_dropsOnFloor_whenProcessHasNoSession() {
        ThinkProcessDocument process = process(null, PROCESS_ID, PROCESS_NAME, null);

        boolean delivered = service.publish(process, "fertig", NotificationSeverity.INFO);

        assertThat(delivered).isFalse();
        verifyNoInteractions(events);
    }

    @Test
    void publish_dropsOnFloor_whenSessionBlank() {
        ThinkProcessDocument process = process("   ", PROCESS_ID, PROCESS_NAME, null);

        boolean delivered = service.publish(process, "fertig", NotificationSeverity.INFO);

        assertThat(delivered).isFalse();
        verify(events, never()).publish(any(), any(), any());
    }

    @Test
    void publish_rejectsNullProcess() {
        assertThatThrownBy(() -> service.publish(null, "x", NotificationSeverity.INFO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("process");
    }

    @Test
    void publish_rejectsBlankText() {
        ThinkProcessDocument process = process(SESSION_ID, PROCESS_ID, PROCESS_NAME, null);
        assertThatThrownBy(() -> service.publish(process, "  ", NotificationSeverity.INFO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    private static ThinkProcessDocument process(
            String sessionId, String processId, String name, String title) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId(processId);
        p.setName(name);
        p.setTitle(title);
        p.setSessionId(sessionId);
        return p;
    }
}
