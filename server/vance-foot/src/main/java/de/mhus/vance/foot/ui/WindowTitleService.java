package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.config.FootConfig;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
 * <p>Title content is rendered from a template configured via
 * {@code vance.ui.window-title.format} (see
 * {@link de.mhus.vance.foot.config.FootConfig.WindowTitle#format}).
 * Supported placeholders: {@code {glyph}}, {@code {session}},
 * {@code {connection}}, {@code {ide}}. Empty placeholders expand to the
 * empty string and trailing whitespace is trimmed, so a clean default
 * like {@code "{glyph} {session}"} renders as just {@code 𝑣} when no
 * session is bound.
 *
 * <p>The {@code {glyph}} placeholder doubles as the vance brand mark and
 * a status indicator driven by a 500 ms blinker:
 * <ul>
 *   <li>{@code 𝑣} — idle (the brain is not busy).</li>
 *   <li>{@code ●} / {@code ○} — alternated while {@link BusyIndicator}
 *       reports an in-flight operation, so the terminal tab visibly
 *       pulses while the chat round-trip is running.</li>
 * </ul>
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
    /** Idle prefix — italic "v" (MATHEMATICAL ITALIC SMALL V) as a nod to "vance". */
    private static final String GLYPH_IDLE = "𝑣";
    /** Busy "on" half of the blink — filled circle. */
    private static final String GLYPH_BUSY_ON = "●";
    /** Busy "off" half of the blink — hollow circle (same shape, just emptied). */
    private static final String GLYPH_BUSY_OFF = "○";
    /** Shown when the expanded title would degrade to nothing more than the glyph. */
    private static final String FALLBACK_LABEL = "[vance]";
    private static final long BLINK_INTERVAL_MS = 500;

    private final FootConfig config;
    private final BusyIndicator busy;
    private final Consumer<String> writer;
    private final BooleanSupplier ttyCheck;

    private final AtomicReference<String> connection = new AtomicReference<>("starting");
    private final AtomicReference<@Nullable String> session = new AtomicReference<>();
    private final AtomicBoolean ideAttached = new AtomicBoolean(false);
    /** Currently rendered prefix glyph — idle, busy-on, or busy-off. */
    private final AtomicReference<String> glyph = new AtomicReference<>(GLYPH_IDLE);

    private final @Nullable ScheduledExecutorService blinker;

    @Autowired
    public WindowTitleService(FootConfig config, BusyIndicator busy) {
        this(config, busy, WindowTitleService::writeToStdout, () -> System.console() != null, true);
    }

    WindowTitleService(FootConfig config,
                       BusyIndicator busy,
                       Consumer<String> writer,
                       BooleanSupplier ttyCheck) {
        this(config, busy, writer, ttyCheck, false);
    }

    private WindowTitleService(FootConfig config,
                               BusyIndicator busy,
                               Consumer<String> writer,
                               BooleanSupplier ttyCheck,
                               boolean startBlinker) {
        this.config = config;
        this.busy = busy;
        this.writer = writer;
        this.ttyCheck = ttyCheck;
        if (startBlinker) {
            ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "foot-window-title-blink");
                th.setDaemon(true);
                return th;
            });
            exec.scheduleAtFixedRate(this::tick,
                    BLINK_INTERVAL_MS, BLINK_INTERVAL_MS, TimeUnit.MILLISECONDS);
            this.blinker = exec;
        } else {
            this.blinker = null;
        }
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
        ScheduledExecutorService exec = blinker;
        if (exec != null) {
            exec.shutdownNow();
        }
        if (!enabled()) return;
        writer.accept(OSC_PREFIX + OSC_TERMINATOR);
    }

    /**
     * Blinker tick — alternates the prefix glyph between {@code ●} and
     * {@code ○} while the brain is busy, snaps back to the idle {@code 𝑣}
     * once the work has settled. Re-emits the title only when the glyph
     * actually changed, to keep idle traffic to zero.
     */
    void tick() {
        if (!enabled()) return;
        String next;
        if (busy.isBusy()) {
            next = GLYPH_BUSY_ON.equals(glyph.get()) ? GLYPH_BUSY_OFF : GLYPH_BUSY_ON;
        } else {
            next = GLYPH_IDLE;
        }
        String prev = glyph.getAndSet(next);
        if (!prev.equals(next)) {
            emit();
        }
    }

    private boolean enabled() {
        return config.getUi().getWindowTitle().isEnabled() && ttyCheck.getAsBoolean();
    }

    private void emit() {
        if (!enabled()) return;
        String fmt = config.getUi().getWindowTitle().getFormat();
        if (fmt == null) fmt = "";
        String s = session.get();
        String c = connection.get();
        String g = glyph.get();
        String title = fmt
                .replace("{glyph}", g)
                .replace("{session}", s == null ? "" : s.strip())
                .replace("{connection}", c == null ? "" : c.strip())
                .replace("{ide}", ideAttached.get() ? "[ide]" : "");
        title = title.stripTrailing();
        // Bare glyph (or fully empty) is unhelpful as a tab label — fall back
        // to the product name so the user always sees something meaningful.
        if (title.isBlank()) {
            title = FALLBACK_LABEL;
        } else if (title.equals(g)) {
            title = g + ' ' + FALLBACK_LABEL;
        }
        writer.accept(OSC_PREFIX + sanitize(title) + OSC_TERMINATOR);
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
