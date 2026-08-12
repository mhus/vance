package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.Terminal;
import java.io.IOException;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class LanternaSessionTest {

    @Test
    void close_stopsScreenBeforeClosingTerminal() throws Exception {
        Screen screen = mock(Screen.class);
        Terminal terminal = mock(Terminal.class);
        LanternaSession session = session(terminal, screen);

        session.close();

        InOrder order = inOrder(screen, terminal);
        order.verify(screen).stopScreen();
        order.verify(terminal).close();
    }

    @Test
    void close_closesTerminalAndPreservesBothCleanupFailures() throws Exception {
        Screen screen = mock(Screen.class);
        Terminal terminal = mock(Terminal.class);
        IOException stopFailure = new IOException("stop failed");
        IOException closeFailure = new IOException("close failed");
        org.mockito.Mockito.doThrow(stopFailure).when(screen).stopScreen();
        org.mockito.Mockito.doThrow(closeFailure).when(terminal).close();
        LanternaSession session = session(terminal, screen);

        assertThatThrownBy(session::close)
                .isSameAs(stopFailure)
                .satisfies(error -> assertThat(error.getSuppressed()).containsExactly(closeFailure));
    }

    @Test
    void close_wrapsRuntimeFailureButStillClosesTerminal() throws Exception {
        Screen screen = mock(Screen.class);
        Terminal terminal = mock(Terminal.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("stop failed")).when(screen).stopScreen();
        LanternaSession session = session(terminal, screen);

        assertThatThrownBy(session::close)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("stop the Lanterna screen")
                .hasCauseInstanceOf(IllegalStateException.class);

        org.mockito.Mockito.verify(terminal).close();
    }

    private static LanternaSession session(Terminal terminal, Screen screen) throws Exception {
        Constructor<LanternaSession> constructor = LanternaSession.class.getDeclaredConstructor(
                Terminal.class, Screen.class, WindowBasedTextGUI.class);
        constructor.setAccessible(true);
        return constructor.newInstance(terminal, screen, mock(WindowBasedTextGUI.class));
    }
}
