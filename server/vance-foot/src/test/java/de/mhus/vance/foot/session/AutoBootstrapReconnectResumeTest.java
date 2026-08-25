package de.mhus.vance.foot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ActiveProcessRef;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.SessionResumeResponse;
import de.mhus.vance.foot.auth.SessionAnchorStore;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.BusyIndicator;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Covers the auto-reconnect session re-adoption: after an unexpected drop the
 * client must take its own session back over ({@code takeover=true}) — a plain
 * resume would be refused because the brain still has it bound to the dead
 * connection, orphaning the user's work.
 */
class AutoBootstrapReconnectResumeTest {

    private final ConnectionService connection = mock(ConnectionService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final ChatTerminal terminal = mock(ChatTerminal.class);
    private final BusyIndicator busy = new BusyIndicator();

    private AutoBootstrapService service() {
        return new AutoBootstrapService(
                new FootConfig(),
                connection,
                mock(BrainRestClientService.class),
                sessions,
                terminal,
                mock(VancePaths.class),
                mock(SessionAnchorStore.class),
                new RandomSessionNameGenerator(),
                busy);
    }

    private void stubResume(SessionResumeResponse response) throws Exception {
        when(connection.request(
                eq(MessageType.SESSION_RESUME),
                any(SessionResumeRequest.class),
                eq(SessionResumeResponse.class),
                any(Duration.class)))
                .thenReturn(response);
    }

    @Test
    void reconnectResume_resumesWithTakeoverAndRebinds() throws Exception {
        stubResume(SessionResumeResponse.builder()
                .sessionId("s1").projectId("p1").chatProcessName("chat").build());

        service().triggerReconnectResume(
                new ConnectionService.ReconnectTarget("s1", "p1", "worker-x"));

        ArgumentCaptor<SessionResumeRequest> req = ArgumentCaptor.forClass(SessionResumeRequest.class);
        verify(connection, timeout(2_000)).request(
                eq(MessageType.SESSION_RESUME), req.capture(),
                eq(SessionResumeResponse.class), any(Duration.class));
        assertThat(req.getValue().getSessionId()).isEqualTo("s1");
        assertThat(req.getValue().isTakeover()).isTrue();

        verify(sessions, timeout(2_000)).bind("s1", "p1");
        // The process the user was steering is restored verbatim.
        verify(sessions, timeout(2_000)).setActiveProcess("worker-x");
    }

    @Test
    void reconnectResume_fallsBackToChatProcessWhenNoActiveProcess() throws Exception {
        stubResume(SessionResumeResponse.builder()
                .sessionId("s1").projectId("p1").chatProcessName("chat-orchestrator").build());

        service().triggerReconnectResume(
                new ConnectionService.ReconnectTarget("s1", "p1", null));

        verify(sessions, timeout(2_000)).setActiveProcess("chat-orchestrator");
    }

    private static ActiveProcessRef active(String id) {
        return ActiveProcessRef.builder().processId(id).name(id).build();
    }

    @Test
    void reconnectResume_restoresBusyStateForProcessesStillRunning() throws Exception {
        stubResume(SessionResumeResponse.builder()
                .sessionId("s1").projectId("p1").chatProcessName("chat")
                .activeProcesses(List.of(active("running-1"))).build());

        service().triggerReconnectResume(
                new ConnectionService.ReconnectTarget("s1", "p1", "chat"));

        // Waits for the line the resync itself writes, not for bind(). The
        // whole flow runs on an executor, and bind() is four statements and a
        // terminal write ahead of resyncBusyState — so "bind happened" says
        // nothing about whether the spinner was restored yet. Anchored there,
        // this test passed alone and failed in the full reactor, where the
        // parallel forks widen exactly that window.
        //
        // This line is emitted after every enterKeyed call, so once it is
        // observed the busy state is final.
        verify(terminal, timeout(2_000)).info(contains("spinner restored"));
        assertThat(busy.isBusy()).isTrue();
        assertThat(busy.depth()).isEqualTo(1);
        // Keyed on the process-id, so the ENGINE_TURN_END that eventually
        // arrives for that process closes the entry the resync opened.
        busy.exitKeyed("running-1", "engine_turn_end:running-1");
        assertThat(busy.isBusy()).isFalse();
    }

    @Test
    void reconnectResume_leavesSpinnerOffWhenNothingRuns() throws Exception {
        stubResume(SessionResumeResponse.builder()
                .sessionId("s1").projectId("p1").chatProcessName("chat")
                .activeProcesses(List.of()).build());

        service().triggerReconnectResume(
                new ConnectionService.ReconnectTarget("s1", "p1", "chat"));

        verify(sessions, timeout(2_000)).bind("s1", "p1");
        assertThat(busy.isBusy()).isFalse();
    }

    @Test
    void reconnectResume_survivesAnOlderBrainThatOmitsTheField() throws Exception {
        // Rolling upgrade: a brain without activeProcesses sends null. The
        // spinner then stays off, which the next turn boundary corrects —
        // an NPE on reconnect would not correct itself.
        stubResume(SessionResumeResponse.builder()
                .sessionId("s1").projectId("p1").chatProcessName("chat").build());

        service().triggerReconnectResume(
                new ConnectionService.ReconnectTarget("s1", "p1", "chat"));

        verify(sessions, timeout(2_000)).bind("s1", "p1");
        assertThat(busy.isBusy()).isFalse();
    }
}
