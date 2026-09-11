package de.mhus.vance.foot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.BootstrappedProcess;
import de.mhus.vance.api.thinkprocess.SessionBootstrapRequest;
import de.mhus.vance.api.thinkprocess.SessionBootstrapResponse;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The new-session path shared by {@code /new} and {@code /ui-new}: which
 * project a fresh session lands in, and that the bootstrap request mirrors
 * the Web-UI modal (no processes, the picked recipe as {@code chatRecipe}).
 */
class NewSessionServiceTest {

    private final ConnectionService connection = mock(ConnectionService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final ChatTerminal terminal = mock(ChatTerminal.class);
    private final FootConfig config = new FootConfig();

    private NewSessionService service;

    @BeforeEach
    void setUp() {
        service = new NewSessionService(connection, sessions, terminal, config);
    }

    @Test
    void resolveProject_boundSessionWinsOverConfiguredBootstrap() {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("sess_1", "instant-hole", null, null));
        config.getBootstrap().setProjectId("configured-project");

        assertThat(service.resolveProject()).isEqualTo("instant-hole");
    }

    @Test
    void resolveProject_fallsBackToConfiguredBootstrapProject() {
        when(sessions.current()).thenReturn(null);
        config.getBootstrap().setProjectId("configured-project");

        assertThat(service.resolveProject()).isEqualTo("configured-project");
    }

    @Test
    void resolveProject_nullWhenNothingBoundOrConfigured() {
        when(sessions.current()).thenReturn(null);

        assertThat(service.resolveProject()).isNull();
    }

    @Test
    void bootstrapNew_sendsTheWebModalRequestAndBinds() throws Exception {
        SessionBootstrapResponse response = SessionBootstrapResponse.builder()
                .sessionId("sess_new")
                .projectId("instant-hole")
                .sessionCreated(true)
                .chatProcessName("arthur")
                .chatEngine("arthur")
                .processesCreated(List.of(BootstrappedProcess.builder()
                        .name("arthur")
                        .engine("arthur")
                        .status(ThinkProcessStatus.RUNNING)
                        .build()))
                .build();
        when(connection.request(
                        eq(MessageType.SESSION_BOOTSTRAP),
                        any(SessionBootstrapRequest.class),
                        eq(SessionBootstrapResponse.class),
                        any(Duration.class)))
                .thenReturn(response);

        service.bootstrapNew("instant-hole", "arthur");

        ArgumentCaptor<SessionBootstrapRequest> payload = ArgumentCaptor.forClass(SessionBootstrapRequest.class);
        verify(connection)
                .request(
                        eq(MessageType.SESSION_BOOTSTRAP),
                        payload.capture(),
                        eq(SessionBootstrapResponse.class),
                        any(Duration.class));
        // Mirrors the Web-UI modal: no extra processes, the recipe as chatRecipe.
        assertThat(payload.getValue().getChatRecipe()).isEqualTo("arthur");
        assertThat(payload.getValue().getProjectId()).isEqualTo("instant-hole");
        assertThat(payload.getValue().getProcesses()).isEmpty();
        verify(sessions).bind("sess_new", "instant-hole");
        verify(sessions).setActiveProcess("arthur");
    }

    @Test
    void bootstrapNew_defaultSelectionSendsNoChatRecipe() throws Exception {
        SessionBootstrapResponse response = SessionBootstrapResponse.builder()
                .sessionId("sess_new")
                .projectId("instant-hole")
                .sessionCreated(true)
                .build();
        when(connection.request(
                        eq(MessageType.SESSION_BOOTSTRAP),
                        any(SessionBootstrapRequest.class),
                        eq(SessionBootstrapResponse.class),
                        any(Duration.class)))
                .thenReturn(response);

        service.bootstrapNew("instant-hole", null);

        ArgumentCaptor<SessionBootstrapRequest> payload = ArgumentCaptor.forClass(SessionBootstrapRequest.class);
        verify(connection)
                .request(
                        eq(MessageType.SESSION_BOOTSTRAP),
                        payload.capture(),
                        eq(SessionBootstrapResponse.class),
                        any(Duration.class));
        assertThat(payload.getValue().getChatRecipe()).isNull();
        verify(sessions).bind("sess_new", "instant-hole");
        // No chat process reported → nothing set active.
        verify(sessions, never()).setActiveProcess(any());
    }
}
