package de.mhus.vance.foot.ui;

import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import java.io.IOException;

/**
 * Holds an active Lanterna {@code Screen} and {@code TextGUI} for the duration
 * of one fullscreen excursion. Created and closed by
 * {@link InterfaceService#runFullscreen}; clients use the GUI to add windows.
 *
 * <p>Closing this session restores the previous terminal contents (Lanterna
 * uses the alternate screen buffer by default), so when the JLine REPL
 * resumes, the user sees their chat history exactly as it was.
 */
public final class LanternaSession implements AutoCloseable {

    private final Terminal terminal;
    private final Screen screen;
    private final WindowBasedTextGUI gui;

    private LanternaSession(Terminal terminal, Screen screen, WindowBasedTextGUI gui) {
        this.terminal = terminal;
        this.screen = screen;
        this.gui = gui;
    }

    public static LanternaSession open() throws IOException {
        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        Screen screen = new TerminalScreen(terminal);
        screen.startScreen();
        WindowBasedTextGUI gui = new MultiWindowTextGUI(screen);
        return new LanternaSession(terminal, screen, gui);
    }

    public WindowBasedTextGUI gui() {
        return gui;
    }

    public Screen screen() {
        return screen;
    }

    @Override
    public void close() throws IOException {
        try {
            screen.stopScreen();
        } finally {
            terminal.close();
        }
    }
}
