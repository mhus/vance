package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.markdown.MarkdownAnsiRenderer;
import de.mhus.vance.foot.markdown.MarkdownRenderState;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * User-facing output sink with a centralized verbosity filter. Consumers
 * call {@link #println(Verbosity, String, Object...)} unconditionally;
 * the threshold check happens here so callers never write
 * {@code if (verbosity > 1)}.
 *
 * <p>This is <strong>not</strong> the SLF4J logger — that one writes to
 * the application log file. {@code ChatTerminal} writes to the visible
 * surface: while a {@link LiveRegion} is attached, all output is
 * routed through {@link LiveRegion#emitStatic(String)} so it slides
 * into the terminal scrollback above the pinned UI block. Without an
 * active region, output falls back to plain {@code stdout}.
 *
 * <p>Streaming (partial chat chunks) is handled by {@link #streamRaw}:
 * incoming fragments are buffered until a {@code \n} arrives, then the
 * full line is committed as a static line. This loses true real-time
 * character streaming but keeps the live region coherent.
 */
@Component
public class ChatTerminal {

    /** Maximum lines kept in the {@link #buffer} ring. Bounded so a long-running
     * REPL does not grow memory unbounded. */
    private static final int BUFFER_LIMIT = 500;

    private final AtomicReference<Verbosity> threshold = new AtomicReference<>(Verbosity.INFO);
    private final AtomicReference<@Nullable Terminal> jlineTerminal = new AtomicReference<>();
    private final PrintWriter stdoutWriter = new PrintWriter(System.out, true);
    private final Deque<Line> buffer = new ArrayDeque<>(BUFFER_LIMIT);
    private final Object bufferLock = new Object();
    private final FootConfig config;
    private final LiveRegion liveRegion;
    private final AtomicReference<@Nullable MarkdownAnsiRenderer> markdownRenderer = new AtomicReference<>();
    private final AtomicReference<@Nullable MarkdownRenderState> markdownState = new AtomicReference<>();

    private final @Nullable AttributedStyle styleChat;
    private final @Nullable AttributedStyle styleWorker;
    private final @Nullable AttributedStyle styleInfo;
    private final @Nullable AttributedStyle styleVerbose;
    private final @Nullable AttributedStyle styleDebug;
    private final @Nullable AttributedStyle styleWarn;
    private final @Nullable AttributedStyle styleError;

    /** Buffer for partial stream chunks until a newline lets us flush. */
    private final StringBuilder streamBuffer = new StringBuilder();
    private static final int STREAM_BUFFER_FORCE_FLUSH = 4096;

    public ChatTerminal(FootConfig config, LiveRegion liveRegion) {
        this.config = config;
        this.liveRegion = liveRegion;
        FootConfig.Colors c = config.getUi().getColors();
        this.styleChat = StyleParser.parse(c.getChat());
        this.styleWorker = StyleParser.parse(c.getWorker());
        this.styleInfo = StyleParser.parse(c.getInfo());
        this.styleVerbose = StyleParser.parse(c.getVerbose());
        this.styleDebug = StyleParser.parse(c.getDebug());
        this.styleWarn = StyleParser.parse(c.getWarn());
        this.styleError = StyleParser.parse(c.getError());
    }

    /**
     * Setter injection so the optional markdown wiring doesn't widen the
     * constructor signature (kept stable for hand-built test stubs).
     * Called automatically by Spring; tests that don't need markdown
     * rendering can leave it unset.
     */
    @Autowired(required = false)
    public void setMarkdown(MarkdownAnsiRenderer renderer, MarkdownRenderState state) {
        this.markdownRenderer.set(renderer);
        this.markdownState.set(state);
    }

    public Verbosity threshold() {
        return threshold.get();
    }

    /**
     * Best-effort terminal column count. Returns the JLine terminal's
     * reported width when attached, otherwise {@code 80} as a safe
     * fallback.
     */
    public int width() {
        Terminal t = jlineTerminal.get();
        if (t == null) return 80;
        int w = t.getWidth();
        return w > 0 ? w : 80;
    }

    public void setThreshold(Verbosity newThreshold) {
        threshold.set(newThreshold);
    }

    /**
     * Bind to a JLine terminal so {@link #width()} reports correctly and
     * styled output can target the right capabilities. Pass {@code null}
     * to detach.
     */
    public void attach(@Nullable Terminal terminal) {
        jlineTerminal.set(terminal);
    }

    public void println(Verbosity level, String message) {
        if (!threshold.get().shows(level)) {
            return;
        }
        emitWithStyle(level, message, styleFor(level), truncates(level));
    }

    public void println(Verbosity level, String format, @Nullable Object... args) {
        if (!threshold.get().shows(level)) {
            return;
        }
        emitWithStyle(level, String.format(format, args), styleFor(level), truncates(level));
    }

    /**
     * Println variant that accepts a JLine {@link AttributedString} so
     * callers can style output (faint gray, colors, bold) without
     * hand-rolling ANSI escapes. The styled string is rendered to ANSI
     * and emitted as one static line.
     */
    public void printlnStyled(Verbosity level, AttributedString styled) {
        if (!threshold.get().shows(level)) {
            return;
        }
        String plain = styled.toString();
        record(level, plain);
        emitStyled(styled);
    }

    public void error(String message) { println(Verbosity.ERROR, message); }
    public void warn(String message)  { println(Verbosity.WARN, message); }
    public void info(String message)  { println(Verbosity.INFO, message); }
    public void verbose(String message) { println(Verbosity.VERBOSE, message); }
    public void debug(String message)   { println(Verbosity.DEBUG, message); }

    /**
     * Renders the main-process chat reply, never truncated.
     */
    public void chat(String message) {
        if (!threshold.get().shows(Verbosity.INFO)) {
            return;
        }
        emitWithStyle(Verbosity.INFO, message, styleChat, false);
    }

    /**
     * Renders an assistant chat turn through the lite markdown renderer
     * (headings coloured, tables rendered, {@code **bold**} translated
     * to ANSI etc.) when the toggle is on. Header is emitted as a plain
     * styled line; {@code content} is fed through the renderer. When
     * the toggle is off — or no renderer wired (tests) — the call falls
     * through to {@link #chat(String)} with header and content
     * concatenated, matching the legacy behaviour bit-for-bit.
     */
    public void chatMarkdown(String header, String content) {
        if (!threshold.get().shows(Verbosity.INFO)) return;
        MarkdownRenderState state = markdownState.get();
        MarkdownAnsiRenderer renderer = markdownRenderer.get();
        if (state == null || renderer == null || !state.isEnabled()) {
            chat(header + content);
            return;
        }
        emitWithStyle(Verbosity.INFO, header, styleChat, false);
        for (AttributedString line : renderer.render(content)) {
            String plain = line.toString();
            record(Verbosity.INFO, plain);
            if (line.length() == 0) {
                emit("");
            } else {
                emitStyled(line);
            }
        }
    }

    /**
     * Echoes a line the user just submitted (REPL or REST). Multi-line input
     * is split on {@code \n} and emitted as separate static lines, each with
     * a {@code ❯ } prefix. Never truncated — the user wants to see exactly
     * what was sent, in full. Renders plain (no inverse-video) so it stays
     * readable in dumb terminals; the JLine REPL keeps its own inverse-video
     * echo for visual consistency with the prompt.
     */
    public void echoInput(String line) {
        if (line == null || line.isEmpty()) return;
        if (!threshold.get().shows(Verbosity.INFO)) {
            return;
        }
        for (String segment : line.split("\n", -1)) {
            String prefixed = "❯ " + segment;
            record(Verbosity.INFO, prefixed);
            emit(prefixed);
        }
    }

    /**
     * Renders a sub-process (worker) chat echo, truncated to
     * {@code vance.ui.lineMaxChars}.
     */
    public void worker(String message) {
        if (!threshold.get().shows(Verbosity.INFO)) {
            return;
        }
        emitWithStyle(Verbosity.INFO, message, styleWorker, true);
    }

    private @Nullable AttributedStyle styleFor(Verbosity level) {
        return switch (level) {
            case ERROR -> styleError;
            case WARN -> styleWarn;
            case INFO -> styleInfo;
            case VERBOSE -> styleVerbose;
            case DEBUG, TRACE -> styleDebug;
        };
    }

    private boolean truncates(Verbosity level) {
        return switch (level) {
            case ERROR, WARN -> false;
            case INFO, VERBOSE, DEBUG, TRACE -> true;
        };
    }

    private void emitWithStyle(Verbosity level, String message,
                                @Nullable AttributedStyle style, boolean truncate) {
        String visible = truncate ? truncate(message) : message;
        record(level, visible);
        if (style == null) {
            emit(visible);
            return;
        }
        AttributedString styled = new AttributedStringBuilder()
                .style(style)
                .append(visible)
                .toAttributedString();
        emitStyled(styled);
    }

    private String truncate(String message) {
        int max = config.getUi().getLineMaxChars();
        if (max <= 0 || message == null || message.length() <= max) {
            return message;
        }
        int cut = Math.max(0, max - 3);
        return message.substring(0, cut) + "...";
    }

    private void emitStyled(AttributedString styled) {
        if (liveRegion.isAttached()) {
            Terminal t = jlineTerminal.get();
            String ansi = t != null ? styled.toAnsi(t) : styled.toAnsi();
            liveRegion.emitStatic(ansi);
            return;
        }
        // Fallback: no live region — write directly via stdout.
        Terminal t = jlineTerminal.get();
        PrintWriter w = writer();
        w.println(t != null ? styled.toAnsi(t) : styled.toAnsi());
        w.flush();
    }

    private void emit(String line) {
        if (liveRegion.isAttached()) {
            liveRegion.emitStatic(line);
            return;
        }
        PrintWriter w = writer();
        w.println(line);
        w.flush();
    }

    /**
     * Streaming-chunk writer. Buffers fragments until a {@code \n}
     * lands, then commits the assembled line via
     * {@link LiveRegion#emitStatic(String)}. If the buffer grows past
     * {@link #STREAM_BUFFER_FORCE_FLUSH} characters without a newline,
     * it is force-flushed anyway so a runaway stream doesn't grow
     * unbounded.
     */
    public void streamRaw(String text) {
        if (text == null || text.isEmpty()) return;
        if (!liveRegion.isAttached()) {
            PrintWriter w = writer();
            w.print(text);
            w.flush();
            return;
        }
        synchronized (streamBuffer) {
            streamBuffer.append(text);
            // Drain complete lines.
            int nl;
            while ((nl = streamBuffer.indexOf("\n")) >= 0) {
                String line = streamBuffer.substring(0, nl);
                streamBuffer.delete(0, nl + 1);
                // Strip a trailing CR if the source uses CRLF.
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                liveRegion.emitStatic(line);
            }
            // Force-flush if the buffer has grown too long without a newline.
            if (streamBuffer.length() > STREAM_BUFFER_FORCE_FLUSH) {
                String tail = streamBuffer.toString();
                streamBuffer.setLength(0);
                liveRegion.emitStatic(tail);
            }
        }
    }

    private PrintWriter writer() {
        Terminal t = jlineTerminal.get();
        return t != null ? t.writer() : stdoutWriter;
    }

    /**
     * Wipes the recorded buffer and, if a live region is active, clears
     * the visible screen and re-paints the block. Called by
     * {@code /clear}.
     */
    public void clearScreen() {
        synchronized (bufferLock) {
            buffer.clear();
        }
        if (liveRegion.isAttached()) {
            liveRegion.clearScreen();
            return;
        }
        Terminal t = jlineTerminal.get();
        if (t != null) {
            t.puts(org.jline.utils.InfoCmp.Capability.clear_screen);
            t.flush();
        }
    }

    /**
     * Fire the terminal bell ({@code BEL} — capability {@code bell}).
     * Most terminals beep, flash, or both depending on user prefs;
     * a few drop it silently. Either way the call is best-effort and
     * never blocks. Used by the user-notification side-channel
     * ({@code MessageType.NOTIFY}) to grab the user's attention.
     */
    public void bell() {
        Terminal t = jlineTerminal.get();
        if (t == null) return;
        try {
            t.puts(org.jline.utils.InfoCmp.Capability.bell);
            t.flush();
        } catch (RuntimeException ignored) {
            // Terminal capability missing on this stack — silent drop.
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

    /**
     * Records a fully-assembled chat line into the scrollback buffer
     * without rendering. Used by {@link StreamingDisplay} to mirror
     * inline-streamed assistant turns into {@code /debug/output}: the
     * user already saw the text via {@link #streamRaw} chunks, but the
     * debug REST surface only inspects the recorded buffer.
     */
    public void recordChat(String message) {
        record(Verbosity.INFO, message);
    }

    /** One captured terminal line. */
    public record Line(Instant timestamp, Verbosity level, String text) {}
}
