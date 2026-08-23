package de.mhus.vance.foot.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.foot.agent.ClientAgentDocService;
import de.mhus.vance.foot.auth.ProjectBindingApplier;
import de.mhus.vance.foot.auth.ProjectBindingStore;
import de.mhus.vance.foot.auth.SessionAnchor;
import de.mhus.vance.foot.auth.SessionAnchorStore;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.command.SkillCommandHelper;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.config.VanceProjectConfigApplier;
import de.mhus.vance.foot.config.VanceProjectConfigStore;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ide.IdeBridgeService;
import de.mhus.vance.foot.markdown.MarkdownRenderState;
import de.mhus.vance.foot.permission.PermissionService;
import de.mhus.vance.foot.session.AutoBootstrapService;
import de.mhus.vance.foot.session.LocalSessionPickerView;
import de.mhus.vance.foot.session.SessionResumeFlow;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.tools.ClientToolService;
import de.mhus.vance.foot.tools.pack.FootToolPackRegistry;
import de.mhus.vance.foot.tools.pack.ProjectPackConsent;
import de.mhus.vance.foot.transfer.FootTransferService;
import de.mhus.vance.foot.ui.ChatRepl;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.ColorResolver;
import de.mhus.vance.foot.ui.WindowTitleService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Startup wiring of the {@code -c} / {@code --continue} local-session picker:
 * the terminal has to exist before the picker runs, and each of the picker's
 * escape choices has to reach the bootstrap config.
 */
class VanceFootCommandContinueTest {

    private ChatRepl repl;
    private SessionResumeFlow resumeFlow;
    private SessionAnchorStore sessionAnchorStore;
    private PermissionService permissions;
    private FootConfig config;
    private VanceFootCommand command;

    private static final SessionAnchor.SessionEntry NEWEST =
            new SessionAnchor.SessionEntry("newest-session", "proj", null, 200L);
    private static final SessionAnchor.SessionEntry OLDER =
            new SessionAnchor.SessionEntry("older-session", "proj", null, 100L);

    @BeforeEach
    void setUp() throws Exception {
        repl = mock(ChatRepl.class);
        resumeFlow = mock(SessionResumeFlow.class);
        sessionAnchorStore = mock(SessionAnchorStore.class);
        permissions = mock(PermissionService.class);
        config = new FootConfig();

        VancePaths vancePaths = mock(VancePaths.class);
        when(vancePaths.activeDir()).thenReturn(Path.of("."));
        when(sessionAnchorStore.loadEntries(any())).thenReturn(List.of(NEWEST, OLDER));
        when(sessionAnchorStore.file(any())).thenReturn(Path.of("./.vancetope/session.yaml"));
        when(permissions.isSandboxEnabled()).thenReturn(true);

        command = new VanceFootCommand(
                repl,
                mock(ConnectionService.class),
                mock(ChatTerminal.class),
                config,
                mock(IdeBridgeService.class),
                mock(ClientAgentDocService.class),
                mock(ClientToolService.class),
                mock(FootTransferService.class),
                resumeFlow,
                mock(WindowTitleService.class),
                mock(MarkdownRenderState.class),
                permissions,
                vancePaths,
                mock(ProjectBindingStore.class),
                mock(ProjectBindingApplier.class),
                sessionAnchorStore,
                mock(VanceProjectConfigStore.class),
                mock(VanceProjectConfigApplier.class),
                mock(ColorResolver.class),
                mock(SkillCommandHelper.class),
                mock(SessionService.class),
                mock(OneShotTurnGate.class),
                mock(FootToolPackRegistry.class),
                mock(ProjectPackConsent.class));
        command.continueSession = true;
        command.noConnect = true;
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(AutoBootstrapService.SKIP_PROPERTY);
    }

    @Test
    void continuePicker_getsATerminalBeforeItRuns() throws Exception {
        when(resumeFlow.continueFromLocal(any())).thenReturn(
                new LocalSessionPickerView.Result(
                        LocalSessionPickerView.Choice.RESUME_ENTRY, NEWEST));

        command.call();

        // The picker is a Lanterna fullscreen excursion; without a registered
        // JLine terminal it can only fail into its degradation path. The REPL
        // builds that terminal far below, so the command has to force it here.
        InOrder order = inOrder(repl, resumeFlow);
        order.verify(repl).ensureTerminal();
        order.verify(resumeFlow).continueFromLocal(any());
    }

    @Test
    void newSessionChoice_clearsAPinnedBootstrapSessionId() throws Exception {
        config.getBootstrap().setProjectId("proj");
        config.getBootstrap().setSessionId("pinned-from-config");
        when(resumeFlow.continueFromLocal(any())).thenReturn(
                new LocalSessionPickerView.Result(
                        LocalSessionPickerView.Choice.NEW_SESSION, null));

        command.call();

        assertThat(config.getBootstrap().getSessionId()).isNull();
        assertThat(config.getBootstrap().getProjectId()).isEqualTo("proj");
    }

    @Test
    void resumeEntryChoice_pinsThePickedSession() throws Exception {
        config.getBootstrap().setSessionId("pinned-from-config");
        when(resumeFlow.continueFromLocal(any())).thenReturn(
                new LocalSessionPickerView.Result(
                        LocalSessionPickerView.Choice.RESUME_ENTRY, OLDER));

        command.call();

        assertThat(config.getBootstrap().getSessionId()).isEqualTo("older-session");
    }

    @Test
    void allSessionsChoice_handsOverToTheServerPicker() throws Exception {
        when(resumeFlow.continueFromLocal(any())).thenReturn(
                new LocalSessionPickerView.Result(
                        LocalSessionPickerView.Choice.ALL_SESSIONS, null));
        when(resumeFlow.run(anyBoolean(), any(), anyBoolean()))
                .thenReturn(SessionResumeFlow.Outcome.BOOTSTRAPPED);

        command.call();

        verify(resumeFlow).run(false, null, false);
    }

    @Test
    void cancelChoice_exitsWithoutStartingTheRepl() throws Exception {
        when(resumeFlow.continueFromLocal(any())).thenReturn(
                new LocalSessionPickerView.Result(
                        LocalSessionPickerView.Choice.CANCEL, null));

        assertThat(command.call()).isEqualTo(1);

        verify(repl, never()).run();
    }
}
