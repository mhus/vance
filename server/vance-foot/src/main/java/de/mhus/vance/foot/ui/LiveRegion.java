package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.config.FootConfig;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Ink-style live UI region pinned to the bottom of the terminal,
 * plus the input handler that drives it. See
 * {@code readme/foot-status-bar-rendering.md} for the design rationale
 * (why no DECSTBM, why OPOST stays on, why no JLine {@code LineReader}).
 *
 * <p>The region is composed bottom-up as:
 * <pre>
 *   status line                ← provided by {@link StatusBar}
 *   blank divider
 *   input line(s) (multi-line, viewport-scrolled)
 *   hints line                 ← provided by {@link StatusBar}
 * </pre>
 *
 * <p>"Static" content (chat output, system messages) is emitted via
 * {@link #emitStatic(String)} and slides naturally into the terminal's
 * scrollback above the live region. Async writers from other threads
 * must hold {@link #writeLock()} for any direct terminal output, so
 * their bytes don't interleave with ours.
 */
@Component
public class LiveRegion {

    private static final String ESC = "\u001b";

    private static final long ANIMATION_INTERVAL_MS = 120;
    private static final int INPUT_MAX_ROWS_SOFT_CAP = 8;
    private static final int CONTENT_MIN_ROWS = 1;
    private static final int LIVE_FIXED_ROWS = 3;
    private static final int INPUT_PREFIX = 3;

    private final StatusBar statusBar;
    private final FootConfig config;

    private final Object writeLock = new Object();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicInteger frame = new AtomicInteger();

    private volatile @Nullable Terminal terminal;
    private volatile @Nullable OutputStream out;
    private volatile @Nullable NonBlockingReader reader;
    private volatile @Nullable Attributes previousAttrs;
    private volatile @Nullable ScheduledExecutorService animator;
    private volatile @Nullable Thread inputThread;

    /** Physical rows the live region currently occupies. */
    private int liveLines = 0;
    /** Row index of the visible cursor within the live region (0 = top). */
    private int cursorRowInLive = 0;

    private volatile String inputText = "";
    private volatile int cursorIdx = 0;
    private volatile int inputViewportTop = 0;

    /** Newest-last list of submitted lines for ↑/↓ history navigation. */
    private final java.util.List<String> history = new java.util.ArrayList<>();
    private int historyIdx = 0;
    private String pendingInput = "";

    private volatile @Nullable Consumer<String> submitListener;
    private volatile @Nullable Runnable interruptListener;
    private volatile @Nullable Runnable quitListener;
    private volatile @Nullable LiveCompleter completer;

    /**
     * Lightweight interface for slash-command completion / ghost-text
     * generation. Injected by {@link ChatRepl} (or wherever the wiring
     * happens) so {@code LiveRegion} stays independent of the foot
     * command system.
     */
    @FunctionalInterface
    public interface LiveCompleter {
        /**
         * Returns candidate completions for {@code input} given the
         * caret at {@code cursorIdx}. Empty list = no suggestion.
         * Implementations typically only respond when the current word
         * is a slash-command prefix.
         */
        java.util.List<String> complete(String input, int cursorIdx);
    }

    public LiveRegion(StatusBar statusBar, FootConfig config) {
        this.statusBar = statusBar;
        this.config = config;
    }

    /**
     * The lock other components must hold whenever they write to the
     * terminal directly (raw ANSI, async streams, etc.). Held briefly
     * by paint, erase, and static-emit operations.
     */
    public Object writeLock() {
        return writeLock;
    }

    public void setSubmitListener(@Nullable Consumer<String> listener) {
        this.submitListener = listener;
    }

    public void setInterruptListener(@Nullable Runnable listener) {
        this.interruptListener = listener;
    }

    public void setQuitListener(@Nullable Runnable listener) {
        this.quitListener = listener;
    }

    /**
     * Installs the slash-command completer used by Tab completion and
     * ghost-text autosuggestion. Pass {@code null} to disable.
     */
    public void setCompleter(@Nullable LiveCompleter completer) {
        this.completer = completer;
    }

    /**
     * Replaces the in-memory history with {@code lines} (typically
     * loaded from a persistence file at startup). Caller order matters:
     * oldest first, newest last.
     */
    public synchronized void loadHistory(java.util.List<String> lines) {
        history.clear();
        if (lines != null) {
            for (String s : lines) {
                if (s != null && !s.isEmpty()) history.add(s);
            }
        }
        historyIdx = history.size();
        pendingInput = "";
    }

    /** Appends one submitted line to the history (no persistence). */
    public synchronized void addToHistory(String line) {
        if (line == null || line.isEmpty()) {
            historyIdx = history.size();
            pendingInput = "";
            return;
        }
        if (history.isEmpty() || !history.get(history.size() - 1).equals(line)) {
            history.add(line);
        }
        historyIdx = history.size();
        pendingInput = "";
    }

    public boolean isAttached() {
        return terminal != null;
    }

    public int width() {
        Terminal t = terminal;
        if (t == null) return 80;
        int w = t.getWidth();
        return w > 0 ? w : 80;
    }

    /**
     * Bind to a terminal and start the animation + input threads.
     * Switches the terminal into soft-raw mode (ICANON / ECHO off,
     * OPOST left on) and enables bracketed paste. Idempotent.
     */
    public synchronized void attach(Terminal t) {
        if (terminal != null) return;
        if (!config.getUi().getStatusBar().isEnabled()) {
            return;
        }
        terminal = t;
        out = t.output();
        reader = t.reader();
        Attributes prev = t.getAttributes();
        previousAttrs = prev;
        Attributes mod = new Attributes(prev);
        mod.setLocalFlag(LocalFlag.ICANON, false);
        mod.setLocalFlag(LocalFlag.ECHO, false);
        t.setAttributes(mod);

        // Enable bracketed paste so multi-line pastes don't trigger submit.
        writeRaw(ESC + "[?2004h");

        paintLive();

        ScheduledExecutorService anim = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "foot-live-region-animator");
            th.setDaemon(true);
            return th;
        });
        anim.scheduleAtFixedRate(this::tickAnimate,
                ANIMATION_INTERVAL_MS, ANIMATION_INTERVAL_MS, TimeUnit.MILLISECONDS);
        animator = anim;

        Thread it = new Thread(this::inputLoop, "foot-live-region-input");
        it.setDaemon(true);
        it.start();
        inputThread = it;
    }

    /** Tears down the region: stops threads, restores terminal attributes. */
    @PreDestroy
    public synchronized void detach() {
        if (terminal == null) return;
        stopRequested.set(true);
        ScheduledExecutorService anim = animator;
        if (anim != null) {
            anim.shutdownNow();
            animator = null;
        }
        Thread it = inputThread;
        if (it != null) {
            it.interrupt();
            inputThread = null;
        }
        synchronized (writeLock) {
            eraseLive();
            writeRaw(ESC + "[?2004l");
            writeRaw("\r\n");
        }
        Attributes prev = previousAttrs;
        Terminal t = terminal;
        if (t != null && prev != null) {
            try {
                t.setAttributes(prev);
            } catch (RuntimeException ignored) {
                // best-effort restore
            }
        }
        terminal = null;
        out = null;
        reader = null;
        previousAttrs = null;
    }

    /**
     * Wait until {@link #stopRequested} is set (typically from the input
     * loop on Esc/Ctrl-C/Ctrl-D). Polled, but cheap.
     */
    public void waitUntilStopped() throws InterruptedException {
        while (!stopRequested.get()) {
            Thread.sleep(50);
        }
    }

    /** Request a redraw of the live region (e.g., when status state changed). */
    public void refresh() {
        paintLive();
    }

    /**
     * Emit one or more lines of static content. Embedded {@code \n}
     * characters are honoured — each segment becomes its own scrollback
     * line. The live region is erased, the text is written (terminal
     * auto-scrolls if at the bottom), then the live region is redrawn.
     */
    public void emitStatic(String text) {
        if (text == null || text.isEmpty()) return;
        if (terminal == null) {
            // No live region active — fall back to plain stdout-ish write.
            System.out.println(text);
            return;
        }
        synchronized (writeLock) {
            eraseLive();
            writeRaw(text);
            if (!text.endsWith("\n") && !text.endsWith("\r\n")) {
                writeRaw("\n");
            }
            paintLiveInner();
        }
    }

    /**
     * Clears the terminal screen and the scrollback the way Ctrl-L would,
     * then redraws the live region. Used by the {@code /clear} command.
     */
    public void clearScreen() {
        if (terminal == null) return;
        synchronized (writeLock) {
            // Reset state — we're going to redraw from scratch.
            liveLines = 0;
            cursorRowInLive = 0;
            writeRaw(ESC + "[2J");   // erase entire display
            writeRaw(ESC + "[H");    // cursor home
            paintLiveInner();
        }
    }

    // ─── Animation ─────────────────────────────────────────────────

    private void tickAnimate() {
        frame.incrementAndGet();
        paintLive();
    }

    // ─── Input loop ────────────────────────────────────────────────

    private void inputLoop() {
        NonBlockingReader r = this.reader;
        if (r == null) return;
        try {
            while (!stopRequested.get()) {
                int b = r.read();
                if (b == -1) break;
                if (b == 3 || b == 4) {  // Ctrl-C / Ctrl-D
                    fireQuit();
                    return;
                }
                if (b == 27) {
                    int next = r.read(80L);
                    if (next < 0) {
                        // Lone Esc → interrupt callback (foot used Esc to
                        // pause the active chat process).
                        fireInterrupt();
                        continue;
                    }
                    if (next == 13 || next == 10) {
                        // Option/Alt + Enter → insert newline.
                        insertAtCursor("\n");
                        paintLive();
                        continue;
                    }
                    if (next == '[') {
                        String payload = readCsiPayload(r);
                        handleCsi(payload, r);
                        continue;
                    }
                    // Other meta combos — drop.
                    continue;
                }
                if (b == 9) {  // Tab — slash-command completion
                    handleTab();
                } else if (b == 127 || b == 8) {  // Backspace / DEL
                    if (backspaceAtCursor()) {
                        paintLive();
                    }
                } else if (b == 13 || b == 10) {  // Enter
                    String submitted = inputText;
                    addToHistory(submitted);
                    inputText = "";
                    cursorIdx = 0;
                    inputViewportTop = 0;
                    if (!submitted.isEmpty()) {
                        Consumer<String> sub = submitListener;
                        if (sub != null) {
                            sub.accept(submitted);
                        }
                    }
                    paintLive();
                } else if (b >= 32) {
                    insertAtCursor(String.valueOf((char) b));
                    paintLive();
                }
            }
        } catch (IOException ignored) {
            // stream closed — exit cleanly
        }
        fireQuit();
    }

    private void fireQuit() {
        if (stopRequested.compareAndSet(false, true)) {
            Runnable q = quitListener;
            if (q != null) q.run();
        }
    }

    private void fireInterrupt() {
        Runnable i = interruptListener;
        if (i != null) i.run();
    }

    // ─── Input model ───────────────────────────────────────────────

    private synchronized void insertAtCursor(String s) {
        if (s == null || s.isEmpty()) return;
        int c = Math.min(Math.max(cursorIdx, 0), inputText.length());
        inputText = inputText.substring(0, c) + s + inputText.substring(c);
        cursorIdx = c + s.length();
    }

    private synchronized boolean backspaceAtCursor() {
        int c = Math.min(Math.max(cursorIdx, 0), inputText.length());
        if (c == 0) return false;
        inputText = inputText.substring(0, c - 1) + inputText.substring(c);
        cursorIdx = c - 1;
        return true;
    }

    private synchronized boolean deleteAtCursor() {
        int c = Math.min(Math.max(cursorIdx, 0), inputText.length());
        if (c >= inputText.length()) return false;
        inputText = inputText.substring(0, c) + inputText.substring(c + 1);
        return true;
    }

    private synchronized boolean moveLeft() {
        if (cursorIdx <= 0) return false;
        cursorIdx--;
        return true;
    }

    private synchronized boolean moveRight() {
        if (cursorIdx >= inputText.length()) {
            // At end of buffer — accept the ghost-text suggestion if any.
            String s = currentSuggestion();
            if (!s.isEmpty()) {
                insertAtCursor(s);
                return true;
            }
            return false;
        }
        cursorIdx++;
        return true;
    }

    private synchronized boolean moveHome() {
        int start = lineStart(cursorIdx);
        if (start == cursorIdx) return false;
        cursorIdx = start;
        return true;
    }

    private synchronized boolean moveEnd() {
        int end = lineEnd(cursorIdx);
        if (end == cursorIdx) return false;
        cursorIdx = end;
        return true;
    }

    private synchronized boolean moveUp() {
        int start = lineStart(cursorIdx);
        if (start > 0) {
            int col = cursorIdx - start;
            int prevEnd = start - 1;
            int prevStart = lineStart(prevEnd);
            int prevLen = prevEnd - prevStart;
            cursorIdx = prevStart + Math.min(col, prevLen);
            return true;
        }
        return historyUp();
    }

    private synchronized boolean moveDown() {
        int end = lineEnd(cursorIdx);
        if (end < inputText.length()) {
            int start = lineStart(cursorIdx);
            int col = cursorIdx - start;
            int nextStart = end + 1;
            int nextEnd = lineEnd(nextStart);
            int nextLen = nextEnd - nextStart;
            cursorIdx = nextStart + Math.min(col, nextLen);
            return true;
        }
        return historyDown();
    }

    /** Walks one entry backwards into the history. See {@link #moveUp()}. */
    private synchronized boolean historyUp() {
        if (history.isEmpty() || historyIdx <= 0) return false;
        if (historyIdx == history.size()) {
            pendingInput = inputText;
        }
        historyIdx--;
        inputText = history.get(historyIdx);
        cursorIdx = inputText.length();
        inputViewportTop = 0;
        return true;
    }

    /** Walks one entry forward through the history. See {@link #moveDown()}. */
    private synchronized boolean historyDown() {
        if (historyIdx >= history.size()) return false;
        historyIdx++;
        inputText = (historyIdx == history.size()) ? pendingInput : history.get(historyIdx);
        cursorIdx = inputText.length();
        inputViewportTop = 0;
        return true;
    }

    /**
     * Ghost-text suggestion for autosuggestion: tail of the most likely
     * continuation. Empty when the caret isn't at the end of a
     * single-line buffer. Source priority: slash-command completer →
     * history.
     */
    private String currentSuggestion() {
        if (inputText.isEmpty()) return "";
        if (cursorIdx != inputText.length()) return "";
        if (inputText.indexOf('\n') >= 0) return "";
        LiveCompleter c = completer;
        if (c != null && inputText.startsWith("/")) {
            java.util.List<String> cands = c.complete(inputText, cursorIdx);
            if (cands.size() == 1) {
                String full = cands.get(0);
                if (full.length() > inputText.length() && full.startsWith(inputText)) {
                    return full.substring(inputText.length());
                }
            } else if (!cands.isEmpty()) {
                String common = commonPrefix(cands);
                if (common.length() > inputText.length()) {
                    return common.substring(inputText.length());
                }
            }
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            String h = history.get(i);
            if (h.length() > inputText.length()
                    && h.startsWith(inputText)
                    && h.indexOf('\n') < 0) {
                return h.substring(inputText.length());
            }
        }
        return "";
    }

    /**
     * Tab-completion. Completes the first slash-command word on the
     * current logical line. Single match → insert tail + space.
     * Multiple matches with longer common prefix → insert the common
     * extension. Multiple matches with no extension → emit candidate
     * list as a static (scrollback) line.
     */
    private void handleTab() {
        LiveCompleter c = completer;
        if (c == null) return;
        int caret = Math.min(Math.max(cursorIdx, 0), inputText.length());
        int lineBegin = caret == 0 ? 0 : inputText.lastIndexOf('\n', caret - 1) + 1;
        String lineSoFar = inputText.substring(lineBegin, caret);
        if (!lineSoFar.startsWith("/") || lineSoFar.contains(" ")) {
            return;
        }
        java.util.List<String> cands = c.complete(lineSoFar, caret);
        if (cands.isEmpty()) return;

        if (cands.size() == 1) {
            String full = cands.get(0);
            insertAtCursor(full.substring(lineSoFar.length()) + " ");
            paintLive();
            return;
        }
        String common = commonPrefix(cands);
        if (common.length() > lineSoFar.length()) {
            insertAtCursor(common.substring(lineSoFar.length()));
            paintLive();
            return;
        }
        emitStatic("  " + String.join("   ", cands));
    }

    private static String commonPrefix(java.util.List<String> ss) {
        if (ss.isEmpty()) return "";
        String p = ss.get(0);
        for (String s : ss) {
            int i = 0;
            int max = Math.min(p.length(), s.length());
            while (i < max && p.charAt(i) == s.charAt(i)) i++;
            p = p.substring(0, i);
            if (p.isEmpty()) return p;
        }
        return p;
    }

    private int lineStart(int idx) {
        int i = Math.min(idx, inputText.length());
        int nl = inputText.lastIndexOf('\n', i - 1);
        return nl < 0 ? 0 : nl + 1;
    }

    private int lineEnd(int idx) {
        int i = Math.min(idx, inputText.length());
        int nl = inputText.indexOf('\n', i);
        return nl < 0 ? inputText.length() : nl;
    }

    private int[] cursorLineAndCol() {
        int c = Math.min(Math.max(cursorIdx, 0), inputText.length());
        int line = 0, col = 0;
        for (int i = 0; i < c; i++) {
            if (inputText.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new int[] { line, col };
    }

    // ─── CSI parser ────────────────────────────────────────────────

    private void handleCsi(String payload, NonBlockingReader r) throws IOException {
        if (payload == null || payload.isEmpty()) return;
        if ("200~".equals(payload)) {
            readPaste(r);
            return;
        }
        if ("201~".equals(payload)) {
            return; // stray paste-end; ignore
        }
        boolean changed = false;
        switch (payload) {
            case "A":  changed = moveUp();    break;
            case "B":  changed = moveDown();  break;
            case "C":  changed = moveRight(); break;
            case "D":  changed = moveLeft();  break;
            case "H":
            case "1~":
            case "7~": changed = moveHome();  break;
            case "F":
            case "4~":
            case "8~": changed = moveEnd();   break;
            case "3~": changed = deleteAtCursor(); break;
            default:
                return;
        }
        if (changed) paintLive();
    }

    private static String readCsiPayload(NonBlockingReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + 200;
        while (System.currentTimeMillis() < deadline) {
            int b = reader.read(80L);
            if (b < 0) break;
            sb.append((char) b);
            if (b >= 0x40 && b <= 0x7e) break;
        }
        return sb.toString();
    }

    private void readPaste(NonBlockingReader reader) throws IOException {
        StringBuilder paste = new StringBuilder();
        while (!stopRequested.get()) {
            int b = reader.read(2000L);
            if (b < 0) break;
            if (b == 27) {
                int n = reader.read(80L);
                if (n == '[') {
                    String body = readCsiPayload(reader);
                    if ("201~".equals(body)) break;
                    continue;
                }
                continue;
            }
            if (b == 13) {
                int peek = reader.read(20L);
                paste.append('\n');
                if (peek >= 0 && peek != 10) {
                    appendPasteByte(paste, peek);
                }
            } else if (b == 10) {
                paste.append('\n');
            } else {
                appendPasteByte(paste, b);
            }
        }
        if (paste.length() > 0) {
            insertAtCursor(paste.toString());
            paintLive();
        }
    }

    private static void appendPasteByte(StringBuilder paste, int b) {
        if (b == 9) {
            paste.append('\t');
        } else if (b >= 32) {
            paste.append((char) b);
        }
    }

    // ─── Painting ──────────────────────────────────────────────────

    private void paintLive() {
        if (terminal == null) return;
        synchronized (writeLock) {
            eraseLive();
            paintLiveInner();
        }
    }

    private void eraseLive() {
        if (liveLines <= 0) return;
        StringBuilder sb = new StringBuilder();
        if (cursorRowInLive > 0) {
            sb.append(ESC).append("[").append(cursorRowInLive).append("A");
        }
        sb.append("\r");
        sb.append(ESC).append("[0J");
        writeRaw(sb.toString());
        liveLines = 0;
        cursorRowInLive = 0;
    }

    private void paintLiveInner() {
        Terminal t = terminal;
        if (t == null) return;
        int width = Math.max(1, t.getWidth() > 0 ? t.getWidth() : 80);
        int maxInputRows = computeMaxInputRows(t);
        String[] lines = buildLiveLines(width, maxInputRows);
        if (lines.length == 0) return;

        int[] physicalRows = new int[lines.length];
        StringBuilder sb = new StringBuilder();
        int totalRows = 0;
        for (int i = 0; i < lines.length; i++) {
            sb.append("\r");
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append("\n");
            int visible = visibleLength(lines[i]);
            int rows = visible == 0 ? 1 : (visible + width - 1) / width;
            physicalRows[i] = rows;
            totalRows += rows;
        }

        // Caret position.
        int inputStart = 2;
        int inputCount = lines.length - 3;
        int[] cl = cursorLineAndCol();
        int cursorLineInViewport = Math.max(0,
                Math.min(cl[0] - inputViewportTop, inputCount - 1));
        int cursorCol = cl[1];
        int caretVisibleCol = INPUT_PREFIX + cursorCol;
        int caretRowOffsetInLine = caretVisibleCol / width;
        int caretPhysicalCol = (caretVisibleCol % width) + 1;
        int caretRowFromTop = 0;
        for (int i = 0; i < inputStart + cursorLineInViewport; i++) {
            caretRowFromTop += physicalRows[i];
        }
        caretRowFromTop += caretRowOffsetInLine;

        int bottomRow = totalRows - 1;
        int up = bottomRow - caretRowFromTop;
        if (up > 0) {
            sb.append(ESC).append("[").append(up).append("A");
        }
        sb.append("\r");
        if (caretPhysicalCol > 1) {
            sb.append(ESC).append("[").append(caretPhysicalCol - 1).append("C");
        }

        writeRaw(sb.toString());
        liveLines = totalRows;
        cursorRowInLive = caretRowFromTop;
    }

    private int computeMaxInputRows(Terminal t) {
        int height = t.getHeight() > 0 ? t.getHeight() : 24;
        int budget = height - LIVE_FIXED_ROWS - CONTENT_MIN_ROWS;
        return Math.max(1, Math.min(INPUT_MAX_ROWS_SOFT_CAP, budget));
    }

    /** Builds the assembled live-region content as an array of logical lines. */
    private String[] buildLiveLines(int width, int maxInputRows) {
        String status = statusBar.buildStatusLine(width, frame.get());
        String hints = statusBar.buildHintsRow(width);

        String[] inputSegs = inputText.split("\n", -1);
        int totalLines = inputSegs.length;
        int[] cl = cursorLineAndCol();
        int cursorLine = Math.max(0, Math.min(cl[0], totalLines - 1));

        if (inputViewportTop > cursorLine) {
            inputViewportTop = cursorLine;
        }
        if (inputViewportTop > Math.max(0, totalLines - 1)) {
            inputViewportTop = Math.max(0, totalLines - 1);
        }
        int visibleCount = countLinesThatFit(inputSegs, inputViewportTop, width, maxInputRows);
        while (cursorLine >= inputViewportTop + visibleCount && inputViewportTop < totalLines - 1) {
            inputViewportTop++;
            visibleCount = countLinesThatFit(inputSegs, inputViewportTop, width, maxInputRows);
        }
        int viewportEnd = Math.min(totalLines, inputViewportTop + visibleCount);
        boolean overflowAbove = inputViewportTop > 0;
        boolean overflowBelow = viewportEnd < totalLines;

        String ghost = currentSuggestion();
        java.util.List<String> assembled = new java.util.ArrayList<>(visibleCount + 3);
        assembled.add(status);
        assembled.add("");
        for (int i = inputViewportTop; i < viewportEnd; i++) {
            String prefix = (i == 0) ? "❯  " : "   ";
            String row = prefix + inputSegs[i];
            if (!ghost.isEmpty() && i == cursorLine) {
                row = row + ESC + "[2m" + ghost + ESC + "[0m";
            }
            boolean isFirstVisible = (i == inputViewportTop);
            boolean isLastVisible = (i == viewportEnd - 1);
            if (isFirstVisible && overflowAbove) {
                row = appendArrow(row, width, "↑");
            }
            if (isLastVisible && overflowBelow) {
                row = appendArrow(row, width, "↓");
            }
            assembled.add(row);
        }
        assembled.add(hints);
        return assembled.toArray(new String[0]);
    }

    private static int countLinesThatFit(String[] segs, int top, int width, int maxRows) {
        int rows = 0;
        int count = 0;
        for (int i = top; i < segs.length; i++) {
            String prefix = (i == 0) ? "❯  " : "   ";
            int visible = prefix.length() + segs[i].length();
            int phys = visible == 0 ? 1 : (visible + width - 1) / width;
            if (count > 0 && rows + phys > maxRows) break;
            rows += phys;
            count++;
            if (rows >= maxRows) break;
        }
        return Math.max(1, count);
    }

    private String appendArrow(String row, int width, String arrow) {
        int visible = visibleLength(row);
        if (visible >= width - 1) return row;
        int pad = width - 1 - visible;
        return row + repeat(' ', pad) + ESC + "[2m" + arrow + ESC + "[0m";
    }

    private static int visibleLength(String s) {
        int len = 0;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == 0x1b) {
                i++;
                if (i < n && s.charAt(i) == '[') {
                    i++;
                    while (i < n) {
                        char x = s.charAt(i);
                        i++;
                        if ((x >= 'A' && x <= 'Z') || (x >= 'a' && x <= 'z')) break;
                    }
                } else if (i < n) {
                    i++;
                }
            } else {
                len++;
                i++;
            }
        }
        return len;
    }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private void writeRaw(String s) {
        OutputStream o = out;
        if (o == null) return;
        try {
            o.write(s.getBytes(StandardCharsets.UTF_8));
            o.flush();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
