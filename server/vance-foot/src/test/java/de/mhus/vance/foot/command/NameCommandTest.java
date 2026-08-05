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
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NameCommandTest {

    private SessionService sessions;
    private SessionAnchorStore anchorStore;
    private VancePaths paths;
    private ChatTerminal terminal;
    private NameCommand command;

    @BeforeEach
    void setUp() {
        sessions = mock(SessionService.class);
        anchorStore = mock(SessionAnchorStore.class);
        paths = mock(VancePaths.class);
        terminal = mock(ChatTerminal.class);
        when(paths.activeDir()).thenReturn(Path.of("/tmp/vance-test"));
        command = new NameCommand(sessions, anchorStore, paths, terminal);
    }

    // ── /name (no args) — show current name ──

    @Test
    void noArgs_showsCurrentName_whenNameSet() throws Exception {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        when(anchorStore.findName(any(Path.class), eq("s1"))).thenReturn("frosty-badger");

        command.execute(List.of());

        verify(terminal).info("Name: frosty-badger");
    }

    @Test
    void noArgs_showsUnset_whenNameIsNull() throws Exception {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        when(anchorStore.findName(any(Path.class), eq("s1"))).thenReturn(null);

        command.execute(List.of());

        verify(terminal).info("Name: (unset)");
    }

    // ── /name <text> — set name ──

    @Test
    void withText_setsName() throws Exception {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));

        command.execute(List.of("my", "custom", "name"));

        verify(anchorStore).renameSession(any(Path.class), eq("s1"), eq("my custom name"));
        verify(terminal).info("Name: my custom name");
    }

    @Test
    void withSingleWord_setsName() throws Exception {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));

        command.execute(List.of("alpha"));

        verify(anchorStore).renameSession(any(Path.class), eq("s1"), eq("alpha"));
        verify(terminal).info("Name: alpha");
    }

    // ── /name "" — clear name ──

    @Test
    void emptyQuotedString_clearsName() throws Exception {
        when(sessions.current()).thenReturn(new SessionService.BoundSession("s1", "p1", null, null));

        command.execute(List.of("\"\""));

        verify(anchorStore).renameSession(any(Path.class), eq("s1"), eq(null));
        verify(terminal).info("Name: (cleared)");
    }

    // ── no session bound ──

    @Test
    void noSessionBound_printsError() throws Exception {
        when(sessions.current()).thenReturn(null);

        command.execute(List.of("whatever"));

        verify(terminal).error("No session bound — use /session-resume or /session-bootstrap first.");
        verifyNoInteractions(anchorStore);
    }

    @Test
    void noSessionBound_noArgs_printsError() throws Exception {
        when(sessions.current()).thenReturn(null);

        command.execute(List.of());

        verify(terminal).error("No session bound — use /session-resume or /session-bootstrap first.");
        verifyNoInteractions(anchorStore);
    }

    // ── metadata ──

    @Test
    void name_and_description_areSet() {
        assertThat(command.name()).isEqualTo("name");
        assertThat(command.description()).contains("/name");
    }
}
