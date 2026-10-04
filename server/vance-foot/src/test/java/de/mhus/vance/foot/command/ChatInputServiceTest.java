package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.foot.audit.ConversationAuditService;
import de.mhus.vance.foot.chat.PendingAskUserPicker;
import de.mhus.vance.foot.chat.QueuedSendState;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ide.IdeContextBuilder;
import de.mhus.vance.foot.permission.PendingPermissionPrompt;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.BusyIndicator;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.PendingLinePrompt;
import de.mhus.vance.foot.ui.PromptGate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChatInputServiceTest {

    private final ConnectionService connection = mock(ConnectionService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final BusyIndicator busyIndicator = mock(BusyIndicator.class);
    private final PendingPermissionPrompt pendingPermission = mock(PendingPermissionPrompt.class);
    private final PendingLinePrompt pendingLine = mock(PendingLinePrompt.class);
    private final QueuedSendState queuedSends = new QueuedSendState();
    private final IdeContextBuilder ideContextBuilder = mock(IdeContextBuilder.class);
    private final AutoAiService autoAi = mock(AutoAiService.class);

    private ChatInputService newService() {
        return new ChatInputService(
                mock(CommandService.class),
                connection,
                sessions,
                mock(ChatTerminal.class),
                mock(PromptGate.class),
                busyIndicator,
                ideContextBuilder,
                mock(PendingAskUserPicker.class),
                pendingPermission,
                pendingLine,
                autoAi,
                mock(ConversationAuditService.class),
                new PendingAttachmentService(),
                mock(AttachmentUploadService.class),
                queuedSends);
    }

    @Test
    void requestPauseFromInterrupt_whileNotBusy_stillSendsPause() {
        // The busy counter is a client-side reconstruction from turn
        // boundary pings and goes stale on a reconnect mid-turn. ESC must
        // reach the brain regardless — the brain decides what is actually
        // interruptible (SessionLifecycleService#isInterruptible).
        when(busyIndicator.isBusy()).thenReturn(false);
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        when(connection.send(any())).thenReturn(true);

        newService().requestPauseFromInterrupt();

        verify(connection).send(any());
    }

    @Test
    void requestPauseFromInterrupt_whenBusy_sendsPause() {
        when(busyIndicator.isBusy()).thenReturn(true);
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        when(connection.send(any())).thenReturn(true);

        newService().requestPauseFromInterrupt();

        verify(connection).send(any());
    }

    @Test
    void requestPauseFromInterrupt_withoutBoundSession_sendsNothing() {
        when(sessions.current()).thenReturn(null);

        newService().requestPauseFromInterrupt();

        verify(connection, never()).send(any());
    }

    // ─── offerToActivePrompt ────────────────────────────────────────────
    // The seam that keeps a remote prompt answer off the single-threaded chat
    // executor. That thread is typically blocked inside the very round-trip
    // whose tool call is waiting for the answer, so queueing it there would
    // deadlock until the prompt times out into a deny.

    @Test
    void offerToActivePrompt_deliversToAWaitingLinePrompt() {
        when(pendingLine.offerAnswer("hunter2")).thenReturn(true);

        assertThat(newService().offerToActivePrompt("hunter2")).isTrue();
        verify(pendingPermission, never()).offerAnswer(any());
    }

    @Test
    void offerToActivePrompt_deliversToAWaitingPermissionPrompt() {
        when(pendingLine.offerAnswer(any())).thenReturn(false);
        when(pendingPermission.offerAnswer("1")).thenReturn(true);

        assertThat(newService().offerToActivePrompt("1")).isTrue();
    }

    @Test
    void offerToActivePrompt_blankIsALinePromptAnswerButNeverAPermissionOne() {
        when(pendingLine.offerAnswer("")).thenReturn(false);

        // Blank is a valid line-prompt answer (accept default), so it must be
        // offered there — but an empty string is not a menu choice.
        assertThat(newService().offerToActivePrompt("")).isFalse();
        verify(pendingLine).offerAnswer("");
        verify(pendingPermission, never()).offerAnswer(any());
    }

    @Test
    void offerToActivePrompt_withoutAnyPrompt_leavesTheLineToTheCaller() {
        when(pendingLine.offerAnswer(any())).thenReturn(false);
        when(pendingPermission.offerAnswer(any())).thenReturn(false);

        assertThat(newService().offerToActivePrompt("hello there")).isFalse();
    }

    @Test
    void offerToActivePrompt_trimsBeforeOfferingToThePermissionMenu() {
        when(pendingLine.offerAnswer(any())).thenReturn(false);
        when(pendingPermission.offerAnswer("2")).thenReturn(true);

        assertThat(newService().offerToActivePrompt("  2  ")).isTrue();
    }

    @Test
    void sendChat_whileATurnRuns_notesTheQueueAndTracksTheMessageId() throws Exception {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        when(sessions.activeProcess()).thenReturn("chat");
        when(autoAi.apply(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ideContextBuilder.buildAndConsumeForSteer()).thenReturn(Optional.empty());
        when(connection.request(any(), any(), any(), any()))
                .thenReturn(ProcessSteerResponse.builder()
                        .thinkProcessId("tp-1")
                        .processName("chat")
                        .messageId("msg-7")
                        .queueDepth(2)
                        .status(ThinkProcessStatus.RUNNING)
                        .build());

        ChatInputService.InputResult result = newService().sendChat("hello", ChatInputService.DEFAULT_CHAT_TIMEOUT);

        assertThat(result.ok()).isTrue();
        assertThat(queuedSends.pickup(List.of("msg-7")))
                .as("the acked message is watched until the engine picks it up at a loop boundary")
                .isEqualTo(1);
    }

    @Test
    void sendChat_whileIdle_doesNotTrackTheMessage() throws Exception {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        when(sessions.activeProcess()).thenReturn("chat");
        when(autoAi.apply(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ideContextBuilder.buildAndConsumeForSteer()).thenReturn(Optional.empty());
        when(connection.request(any(), any(), any(), any()))
                .thenReturn(ProcessSteerResponse.builder()
                        .processName("chat")
                        .messageId("msg-8")
                        .queueDepth(1)
                        .status(ThinkProcessStatus.IDLE)
                        .build());

        newService().sendChat("hello", ChatInputService.DEFAULT_CHAT_TIMEOUT);

        assertThat(queuedSends.pickup(List.of("msg-8")))
                .as("a send with no running turn goes straight into the next one — nothing to wait for")
                .isZero();
    }
}
