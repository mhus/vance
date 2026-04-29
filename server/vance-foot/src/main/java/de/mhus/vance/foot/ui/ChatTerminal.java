package de.mhus.vance.foot.ui;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * User-facing output sink with a centralized verbosity filter. Consumers call
 * {@link #println(Verbosity, String, Object...)} unconditionally; the threshold
 * check happens here so callers never write {@code if (verbosity > 1)}.
 *
 * <p>This is <strong>not</strong> the SLF4J logger — that one writes to the
 * application log file. {@code ChatTerminal} writes to whatever surface the
 * user is actually looking at: stdout while no JLine terminal is attached,
 * the JLine writer once one is.
 *
 * <p>Streaming (partial chat chunks) is not modelled here yet — when the first
 * {@code chat-message-stream-chunk} flows in we will add a dedicated method
 * that uses JLine's {@code Status}/{@code Display} API. Until then, callers
 * should buffer chunks and call {@link #println} with the final line.
 */
@Component
public class ChatTerminal {

    /** Maximum lines kept in the {@link #buffer} ring. Bounded so a long-running
     * REPL does not grow memory unbounded. */
    private static final int BUFFER_LIMIT = 500;

    private final AtomicReference<Verbosity> threshold = new AtomicReference<>(Verbosity.INFO);
    private final AtomicReference<@Nullable Terminal> jlineTerminal = new AtomicReference<>();
    private final AtomicReference<@Nullable LineReader> jlineReader = new AtomicReference<>();
    private final PrintWriter stdoutWriter = new PrintWriter(System.out, true);
    private final Deque<Line> buffer = new ArrayDeque<>(BUFFER_LIMIT);
    private final Object bufferLock = new Object();

    public Verbosity threshold() {
        return threshold.get();
    }

    public void setThreshold(Verbosity newThreshold) {
        threshold.set(newThreshold);
    }

    /**
     * Attach a JLine terminal so output goes through its writer (which respects
     * the line-editor's cursor position). Pass {@code null} to detach.
     */
    public void attach(@Nullable Terminal terminal) {
        jlineTerminal.set(terminal);
    }

    /**
     * Attach the active {@link LineReader}. While set, async output goes
     * through {@link LineReader#printAbove(String)} so it appears above the
     * prompt without garbling the line currently being edited. Pass
     * {@code null} on shutdown.
     */
    public void attachReader(@Nullable LineReader reader) {
        jlineReader.set(reader);
    }

    public void println(Verbosity level, String message) {
        if (!threshold.get().shows(level)) {
            return;
        }
        record(level, message);
        emit(message);
    }

    public void println(Verbosity level, String format, @Nullable Object... args) {
        if (!threshold.get().shows(level)) {
            return;
        }
        String formatted = String.format(format, args);
        record(level, formatted);
        emit(formatted);
    }

    /**
     * Println variant that accepts a JLine {@link AttributedString} so
     * callers can style output (faint gray, colors, bold) without
     * hand-rolling ANSI escapes. Threshold and ring-buffer behaviour
     * match {@link #println(Verbosity, String)} — the buffer records
     * the plain text only, escapes do not leak into the debug-REST
     * tail view.
     *
     * <p>When a JLine reader is attached, the styled overload of
     * {@code printAbove} is used so JLine emits the right escape
     * sequence for the terminal's actual capabilities (or strips
     * them on a non-color terminal). Without a reader we fall back
     * to {@link AttributedString#toAnsi()} on the plain writer.
     */
    public void printlnStyled(Verbosity level, AttributedString styled) {
        if (!threshold.get().shows(level)) {
            return;
        }
        String plain = styled.toString();
        record(level, plain);
        LineReader r = jlineReader.get();
        if (r != null) {
            r.printAbove(styled);
            return;
        }
        Terminal t = jlineTerminal.get();
        PrintWriter w = writer();
        w.println(t != null ? styled.toAnsi(t) : styled.toAnsi());
        w.flush();
    }

    public void error(String message) {
        println(Verbosity.ERROR, message);
    }

    public void info(String message) {
        println(Verbosity.INFO, message);
    }

    public void verbose(String message) {
        println(Verbosity.VERBOSE, message);
    }

    public void debug(String message) {
        println(Verbosity.DEBUG, message);
    }

    private void emit(String line) {
        LineReader r = jlineReader.get();
        if (r != null) {
            // printAbove is the JLine primitive for async output: pushes the
            // line above the prompt and redraws the prompt below, regardless
            // of whether readLine is currently blocked or between iterations.
            r.printAbove(line);
            return;
        }
        PrintWriter w = writer();
        w.println(line);
        w.flush();
    }

    /**
     * Writes {@code text} verbatim to the terminal — no level check, no
     * newline, no JLine {@code printAbove}. Intended for chat streaming:
     * the chunk-handler emits each delta with this method while the REPL
     * is blocked on the brain's steer-reply. Calling it while
     * {@code readLine} is actively waiting for input will corrupt the
     * prompt, so streaming consumers must coordinate with the REPL.
     */
    public void streamRaw(String text) {
        if (text == null || text.isEmpty()) return;
        PrintWriter w = writer();
        w.print(text);
        w.flush();
    }

    private PrintWriter writer() {
        Terminal t = jlineTerminal.get();
        return t != null ? t.writer() : stdoutWriter;
    }

    /**
     * Wipes the recorded buffer and, if a JLine terminal is attached, clears
     * the visible screen with the {@code clear_screen} terminfo capability.
     * Called by {@code /clear}; safe to invoke between {@code readLine} cycles
     * because the next readLine repaints the prompt fresh.
     */
    public void clearScreen() {
        synchronized (bufferLock) {
            buffer.clear();
        }
        Terminal t = jlineTerminal.get();
        if (t != null) {
            t.puts(InfoCmp.Capability.clear_screen);
            t.flush();
        }
    }

    /**
     * Up to {@code limit} most-recent lines (oldest first). Used by the debug
     * REST server to expose what the user has been seeing.
     */
    public List<Line> tail(int limit) {
        synchronized (bufferLock) {
            int size = buffer.size();
            int from = Math.max(0, size - Math.max(1, limit));
            List<Line> out = new ArrayList<>(size - from);
            int i = 0;
            for (Line line : buffer) {
                if (i++ >= from) {
                    out.add(line);
                }
            }
            return out;
        }
    }

    private void record(Verbosity level, String message) {
        Line line = new Line(Instant.now(), level, message);
        synchronized (bufferLock) {
            if (buffer.size() == BUFFER_LIMIT) {
                buffer.removeFirst();
            }
            buffer.addLast(line);
        }
    }

    /** One captured terminal line. */
    public record Line(Instant timestamp, Verbosity level, String text) {}
}
