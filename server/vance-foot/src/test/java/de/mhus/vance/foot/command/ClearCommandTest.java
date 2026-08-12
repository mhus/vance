package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.AutoBootstrapService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClearCommandTest {

    @Test
    void clear_reusesConfiguredBootstrapParametersForFreshSession() throws Exception {
        ChatTerminal terminal = mock(ChatTerminal.class);
        ConnectionService connection = mock(ConnectionService.class);
        SessionService sessions = mock(SessionService.class);
        AutoBootstrapService autoBootstrap = mock(AutoBootstrapService.class);
        FootConfig config = new FootConfig();
        config.getBootstrap().setProjectId("configured-project");
        config.getBootstrap().setSessionId("old-session");
        config.getBootstrap().setChatRecipe("configured-recipe");
        FootConfig.BootstrapProcess process = new FootConfig.BootstrapProcess();
        process.setRecipe("worker-recipe");
        process.setName("worker");
        process.getParams().put("model", "configured-model");
        config.getBootstrap().setProcesses(List.of(process));
        when(sessions.current()).thenReturn(
                new SessionService.BoundSession("bound-session", "current-project", "chat", "chat"));

        new ClearCommand(terminal, connection, sessions, config, autoBootstrap)
                .execute(List.of());

        verify(terminal).clearScreen();
        verify(connection).request(
                eq(MessageType.SESSION_UNBIND), eq(null), eq(Void.class), any(Duration.class));
        verify(sessions).clear();
        assertThat(config.getBootstrap().getProjectId()).isEqualTo("current-project");
        assertThat(config.getBootstrap().getSessionId()).isNull();
        assertThat(config.getBootstrap().getChatRecipe()).isEqualTo("configured-recipe");
        assertThat(config.getBootstrap().getProcesses()).containsExactly(process);
        assertThat(config.getBootstrap().getProcesses().getFirst().getParams())
                .containsEntry("model", "configured-model");
        verify(autoBootstrap).triggerNow();
    }
}
