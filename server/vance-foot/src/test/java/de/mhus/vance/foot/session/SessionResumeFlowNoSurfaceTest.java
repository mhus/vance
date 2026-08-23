package de.mhus.vance.foot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionListResponse;
import de.mhus.vance.api.ws.SessionSummary;
import de.mhus.vance.foot.auth.SessionAnchor;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.ColorResolver;
import de.mhus.vance.foot.ui.InterfaceService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of both startup pickers when there is no interactive surface to
 * draw on — the {@code --no-ui} / no-TTY case. Neither may attempt a Lanterna
 * excursion, and the two have deliberately different fallbacks: {@code -c}
 * degrades to the newest local entry, {@code --resume} cancels with a hint.
 */
class SessionResumeFlowNoSurfaceTest {

    private ConnectionService connection;
    private ChatTerminal terminal;
    private InterfaceService interfaceService;
    private AutoBootstrapService bootstrap;
    private FootConfig config;
    private SessionResumeFlow flow;

    @BeforeEach
    void setUp() {
        connection = mock(ConnectionService.class);
        terminal = mock(ChatTerminal.class);
        interfaceService = mock(InterfaceService.class);
        bootstrap = mock(AutoBootstrapService.class);
        config = new FootConfig();
        flow = new SessionResumeFlow(connection, terminal, interfaceService, config, bootstrap,
                mock(BrainRestClientService.class), mock(ColorResolver.class));
        when(interfaceService.isFullscreenAvailable()).thenReturn(false);
    }

    @Test
    void continueFromLocal_withoutASurface_resumesTheNewestLocalEntry() throws Exception {
        List<SessionAnchor.SessionEntry> entries = List.of(
                new SessionAnchor.SessionEntry("newest", "proj", null, 200L),
                new SessionAnchor.SessionEntry("older", "proj", null, 100L));

        LocalSessionPickerView.Result result = flow.continueFromLocal(entries);

        assertThat(result).isNotNull();
        assertThat(result.choice()).isEqualTo(LocalSessionPickerView.Choice.RESUME_ENTRY);
        assertThat(result.entry().getSessionId()).isEqualTo("newest");
        verify(interfaceService, never()).runFullscreen(any());
    }

    @Test
    void run_withoutASurfaceAndWithoutLast_cancelsInsteadOfAttemptingThePicker() throws Exception {
        SessionSummary candidate = SessionSummary.builder()
                .sessionId("s1")
                .projectId("proj")
                .profile("foot")
                .bound(false)
                .lastActivityAt(1L)
                .build();
        when(connection.request(eq(MessageType.SESSION_LIST), any(),
                eq(SessionListResponse.class), any(Duration.class)))
                .thenReturn(SessionListResponse.builder().sessions(List.of(candidate)).build());

        SessionResumeFlow.Outcome outcome = flow.run(false, null, false);

        assertThat(outcome).isEqualTo(SessionResumeFlow.Outcome.CANCELLED);
        verify(interfaceService, never()).runFullscreen(any());
        verify(bootstrap, never()).triggerNow();
    }

    @Test
    void run_withLast_bootstrapsWithoutAnyPicker() throws Exception {
        SessionSummary candidate = SessionSummary.builder()
                .sessionId("s1")
                .projectId("proj")
                .profile("foot")
                .bound(false)
                .lastActivityAt(1L)
                .build();
        when(connection.request(eq(MessageType.SESSION_LIST), any(),
                eq(SessionListResponse.class), any(Duration.class)))
                .thenReturn(SessionListResponse.builder().sessions(List.of(candidate)).build());
        config.getBootstrap().setReplayMessages(0);

        SessionResumeFlow.Outcome outcome = flow.run(false, null, true);

        assertThat(outcome).isEqualTo(SessionResumeFlow.Outcome.BOOTSTRAPPED);
        assertThat(config.getBootstrap().getSessionId()).isEqualTo("s1");
        verify(bootstrap).triggerNow();
        verify(interfaceService, never()).runFullscreen(any());
    }
}
