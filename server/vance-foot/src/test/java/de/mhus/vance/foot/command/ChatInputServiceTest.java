package de.mhus.vance.foot.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.foot.audit.ConversationAuditService;
import de.mhus.vance.foot.chat.PendingAskUserPicker;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ide.IdeContextBuilder;
import de.mhus.vance.foot.permission.PendingPermissionPrompt;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.BusyIndicator;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.PendingLinePrompt;
import de.mhus.vance.foot.ui.PromptGate;
import org.junit.jupiter.api.Test;

class ChatInputServiceTest {

    private final ConnectionService connection = mock(ConnectionService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final BusyIndicator busyIndicator = mock(BusyIndicator.class);

    private ChatInputService newService() {
        return new ChatInputService(
                mock(CommandService.class),
                connection,
                sessions,
                mock(ChatTerminal.class),
                mock(PromptGate.class),
                busyIndicator,
                mock(IdeContextBuilder.class),
                mock(PendingAskUserPicker.class),
                mock(PendingPermissionPrompt.class),
                mock(PendingLinePrompt.class),
                mock(AutoAiService.class),
                mock(ConversationAuditService.class));
    }

    @Test
    void requestPauseFromInterrupt_whenIdle_sendsNothing() {
        when(busyIndicator.isBusy()).thenReturn(false);

        newService().requestPauseFromInterrupt();

        // No process-pause frame — a lone ESC while idle must not flip
        // an IDLE process to PAUSED (which would mint a bogus
        // "USER INTERRUPTED — RECONSIDER" banner on the next turn).
        verify(connection, never()).send(any());
        verify(sessions, never()).current();
    }

    @Test
    void requestPauseFromInterrupt_whenBusy_sendsPause() {
        when(busyIndicator.isBusy()).thenReturn(true);
        when(sessions.current())
                .thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        when(connection.send(any())).thenReturn(true);

        newService().requestPauseFromInterrupt();

        verify(connection).send(any());
    }
}
