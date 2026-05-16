package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.config.FootConfig;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Sets the surrounding terminal's window/tab title via the OSC 0
 * escape sequence ({@code ESC ] 0 ; <text> BEL}). Modern terminals
 * (IntelliJ built-in, iTerm2, Terminal.app, kitty, ghostty, gnome-terminal,
 * Windows Terminal) interpret OSC 0 as "set both icon name and window
 * title" without disturbing on-screen content, so it is safe to call
 * concurrently with the JLine/LiveRegion paint loop.
 *
 * <p>Title format: {@code vance-foot · <connection> · <session> · [ide]}
 * with the session and IDE segments dropped when their state is empty.
 * The three state slots are independent setters so each subsystem owns
 * exactly its piece:
 * <ul>
 *   <li>{@link #setConnection(String)} — connection lifecycle
 *       (connecting, tenant after welcome, disconnected).</li>
 *   <li>{@link #setSession(String)} — session label from
 *       {@code SessionService} (title or projectId).</li>
 *   <li>{@link #setIdeAttached(boolean)} — Claude Code IDE bridge.</li>
 * </ul>
 *
 * <p>Disabled at runtime when {@code vance.ui.window-title.enabled=false}
 * or when stdout is not a TTY (piped output, daemon log file). The
 * {@code @PreDestroy} hook emits an empty title so the terminal tab
 * falls back to its default label on shutdown.
 */
@Service
public class WindowTitleService {

    static final String OSC_PREFIX = ((char) 0x1B) + "]0;";
    static final String OSC_TERMINATOR = String.valueOf((char) 0x07);
    private static final String PREFIX = "vance-foot";
    private static final String SEPARATOR = " " + ((char) 0xB7) + " ";

    private final FootConfig config;
    private final Consumer<String> writer;
    private final BooleanSupplier ttyCheck;

    private final AtomicReference<String> connection = new AtomicReference<>("starting");
    private final AtomicReference<@Nullable String> session = new AtomicReference<>();
    private final AtomicBoolean ideAttached = new AtomicBoolean(false);

    @Autowired
    public WindowTitleService(FootConfig config) {
        this(config, WindowTitleService::writeToStdout, () -> System.console() != null);
    }

    WindowTitleService(FootConfig config,
                       Consumer<String> writer,
                       BooleanSupplier ttyCheck) {
        this.config = config;
        this.writer = writer;
        this.ttyCheck = ttyCheck;
    }

    public void setConnection(String label) {
        connection.set(label);
        emit();
    }

    public void setSession(@Nullable String label) {
        session.set(label);
        emit();
    }

    public void setIdeAttached(boolean attached) {
        ideAttached.set(attached);
        emit();
    }

    @PreDestroy
    void reset() {
        if (!enabled()) return;
        writer.accept(OSC_PREFIX + OSC_TERMINATOR);
    }

    private boolean enabled() {
        return config.getUi().getWindowTitle().isEnabled() && ttyCheck.getAsBoolean();
    }

    private void emit() {
        if (!enabled()) return;
        StringBuilder b = new StringBuilder(PREFIX).append(SEPARATOR).append(connection.get());
        String s = session.get();
        if (s != null && !s.isBlank()) {
            b.append(SEPARATOR).append(s);
        }
        if (ideAttached.get()) {
            b.append(SEPARATOR).append("[ide]");
        }
        writer.accept(OSC_PREFIX + sanitize(b.toString()) + OSC_TERMINATOR);
    }

    /**
     * Strips control characters (including the {@code BEL} terminator and
     * {@code ESC}) so a session title containing a stray escape cannot
     * prematurely close the OSC sequence and leak text into the user's
     * scrollback.
     */
    private static String sanitize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void writeToStdout(String escape) {
        System.out.print(escape);
        System.out.flush();
    }
}
