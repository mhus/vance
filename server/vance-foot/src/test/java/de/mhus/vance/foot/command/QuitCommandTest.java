package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.foot.auth.SessionAnchorStore;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatRepl;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the disconnect notice printed by {@code /quit}. The notice must
 * include the session id <em>and</em> the client name, the latter resolved
 * from the session anchor (the one {@code /name} can change at runtime) with
 * a fallback to the configured {@code vance.client.name} and finally
 * {@code (unnamed)}.
 */
class QuitCommandTest {

    private ChatRepl repl;
    private SessionService sessions;
    private ChatTerminal terminal;
    private SessionAnchorStore anchorStore;
    private VancePaths paths;
    private FootConfig config;
    private QuitCommand command;

    @BeforeEach
    void setUp() {
        repl = mock(ChatRepl.class);
        sessions = mock(SessionService.class);
        terminal = mock(ChatTerminal.class);
        anchorStore = mock(SessionAnchorStore.class);
        paths = mock(VancePaths.class);
        config = new FootConfig();
        when(paths.activeDir()).thenReturn(Path.of("/tmp/vance-test"));
        command = new QuitCommand(repl, sessions, terminal, anchorStore, paths, config);
    }

    @Test
    void quit_printsDisconnectNoticeWithAnchorName() {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        when(anchorStore.findName(any(Path.class), eq("s1"))).thenReturn("frosty-badger");

        command.execute(List.of());

        verify(terminal).info("Disconnected from session s1 at frosty-badger");
        verify(repl).requestStop();
    }

    @Test
    void quit_fallsBackToConfiguredClientNameWhenAnchorHasNone() {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        when(anchorStore.findName(any(Path.class), eq("s1"))).thenReturn(null);
        config.getClient().setName("explicit-name");

        command.execute(List.of());

        verify(terminal).info("Disconnected from session s1 at explicit-name");
    }

    @Test
    void quit_fallsBackToUnnamedWhenNeitherAnchorNorConfigHasName() {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        when(anchorStore.findName(any(Path.class), eq("s1"))).thenReturn(null);
        // config.getClient().getName() stays null (default)

        command.execute(List.of());

        verify(terminal).info("Disconnected from session s1 at (unnamed)");
    }

    @Test
    void quit_fallsBackToUnnamedWhenAnchorNameIsBlank() {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        when(anchorStore.findName(any(Path.class), eq("s1"))).thenReturn("   ");

        command.execute(List.of());

        verify(terminal).info("Disconnected from session s1 at (unnamed)");
    }

    @Test
    void quit_fallsBackToUnnamedWhenConfigNameIsBlank() {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        when(anchorStore.findName(any(Path.class), eq("s1"))).thenReturn(null);
        config.getClient().setName("");

        command.execute(List.of());

        verify(terminal).info("Disconnected from session s1 at (unnamed)");
    }

    @Test
    void quit_printsNothingWhenNoSessionBound() {
        when(sessions.current()).thenReturn(null);

        command.execute(List.of());

        verifyNoInteractions(terminal);
        verify(repl).requestStop();
    }

    @Test
    void name_and_aliases_areSet() {
        assertThat(command.name()).isEqualTo("quit");
        assertThat(command.aliases()).containsExactly("exit");
    }
}
