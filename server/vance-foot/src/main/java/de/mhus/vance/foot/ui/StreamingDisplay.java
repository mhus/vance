package de.mhus.vance.foot.ui;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.markdown.MarkdownRenderState;
import de.mhus.vance.foot.session.SessionService;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Renders chat-streaming chunks. Two modes, picked per chunk based on
 * {@link PromptGate}:
 *
 * <ul>
 *   <li><b>Exclusive</b> (REPL is processing input, no active prompt) —
 *       the chunk goes straight into the scrollback as a delta. The
 *       assistant's reply grows in place where it will eventually live;
 *       {@code chat-message-appended} just terminates the line with a
 *       newline.</li>
 *   <li><b>Out-of-band</b> (the user is at the prompt) — chunks are
 *       buffered per process. On commit the full assembled text is
 *       flushed via {@link ChatTerminal#info(String)}, which goes
 *       through {@code LineReader.printAbove} and so doesn't corrupt
 *       the prompt. Streaming visibility is lost in this mode (the
 *       reply appears as one line at the end), but the prompt the
 *       user is editing stays intact — that trade-off is the right
 *       one here.</li>
 * </ul>
 *
 * <p>The mode is decided per-chunk; if the gate flips mid-turn (rare —
 * REPL flow is sequential) earlier output stays where it landed.
 */
@Component
public class StreamingDisplay {

    /** Dimmed grey for the reasoning stream, matching the end-of-turn
     *  "💭 thoughts" block in {@code ChatMessageAppendedHandler}. */
    private static final AttributedStyle THOUGHTS_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BRIGHT + AttributedStyle.BLACK);

    private final ChatTerminal terminal;
    private final PromptGate promptGate;
    private final SessionService sessions;
    private final MarkdownRenderState markdownState;
    private final FootConfig config;
    private final ThinkingVisibility thinkingVisibility;
    private final Map<String, ProcessStream> streams = new ConcurrentHashMap<>();
    private final Map<String, ThinkingStream> thinkingStreams = new ConcurrentHashMap<>();

    public StreamingDisplay(ChatTerminal terminal,
                            PromptGate promptGate,
                            SessionService sessions,
                            MarkdownRenderState markdownState,
                            FootConfig config,
                            ThinkingVisibility thinkingVisibility) {
        this.terminal = terminal;
        this.promptGate = promptGate;
        this.sessions = sessions;
        this.markdownState = markdownState;
        this.config = config;
        this.thinkingVisibility = thinkingVisibility;
    }

    /**
     * Append a delta to the per-process <em>reasoning</em> stream and
     * render it live as dimmed, gutter-prefixed lines. Only the main
     * process streams live (worker reasoning would clutter the
     * side-channel); gated by the runtime thinking-visibility toggle
     * (Ctrl+T, initialised from {@code ui.showThoughts}). Deltas are not
     * line-aligned, so complete lines are drained and emitted through
     * {@link ChatTerminal#printlnStyled} (the prompt-safe {@code
     * printAbove} path — safe whether or not the user is at the prompt).
     * The trailing partial line is flushed by {@link #finalizeThinking}.
     */
    public void onThinkingChunk(
            String processId,
            @Nullable String processName,
            @Nullable ChatRole role,
            String delta) {
        if (processId == null || delta == null || delta.isEmpty()) return;
        if (!thinkingVisibility.isShowing()) return;
        if (!isMainProcess(processName)) return;
        ThinkingStream state = thinkingStreams.computeIfAbsent(
                processId, k -> new ThinkingStream());
        synchronized (state) {
            state.streamedLive = true;
            if (!state.headerEmitted) {
                terminal.printlnStyled(Verbosity.INFO, dim("💭 thinking"));
                state.headerEmitted = true;
            }
            state.lineBuf.append(delta);
            int nl;
            while ((nl = state.lineBuf.indexOf("\n")) >= 0) {
                String line = state.lineBuf.substring(0, nl);
                state.lineBuf.delete(0, nl + 1);
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                terminal.printlnStyled(Verbosity.INFO, dim("│ " + line));
            }
        }
    }

    /**
     * Flush the trailing partial reasoning line for a process, if any.
     * Called when the answer starts streaming (to keep the two channels
     * ordered) and again on commit. Idempotent — a flushed buffer stays
     * empty. Does not remove the stream state (the {@code streamedLive}
     * flag must survive until {@link #commitThinking}).
     */
    private void finalizeThinking(String processId) {
        ThinkingStream state = thinkingStreams.get(processId);
        if (state == null) return;
        synchronized (state) {
            if (state.lineBuf.length() > 0) {
                String tail = state.lineBuf.toString();
                state.lineBuf.setLength(0);
                terminal.printlnStyled(Verbosity.INFO, dim("│ " + tail));
            }
        }
    }

    /**
     * Closes the reasoning stream for a process. Returns {@code true}
     * when reasoning was streamed live this turn — the caller then
     * suppresses the end-of-turn "💭 thoughts" block to avoid showing
     * the same reasoning twice.
     */
    public boolean commitThinking(String processId) {
        if (processId == null) return false;
        finalizeThinking(processId);
        ThinkingStream state = thinkingStreams.remove(processId);
        return state != null && state.streamedLive;
    }

    private static AttributedString dim(String text) {
        return new AttributedStringBuilder()
                .style(THOUGHTS_STYLE)
                .append(text)
                .toAttributedString();
    }

    /** Append a delta to the per-process stream. */
    public void onChunk(
            String processId,
            @Nullable String processName,
            @Nullable ChatRole role,
            String chunk) {
        if (processId == null || chunk == null || chunk.isEmpty()) return;
        // The reasoning phase precedes the answer; close out any live
        // reasoning line before the first answer chunk lands so the two
        // don't fuse on one terminal line.
        finalizeThinking(processId);
        ProcessStream state = streams.computeIfAbsent(
                processId, k -> new ProcessStream(processName, role));
        synchronized (state) {
            if (processName != null) state.processName = processName;
            if (role != null) state.role = role;
            // Only the main process (Arthur) streams raw to the
            // terminal — its messages are the user-facing chat. Worker
            // sub-processes buffer their chunks and surface as the
            // dimmed side-channel on commit; the orchestrator picks up
            // their content via the structured-action {@code RELAY}
            // path to make it part of Arthur's voice, avoiding
            // dual-voice confusion in the main scroll.
            boolean main = isMainProcess(state.processName);
            // Markdown-render mode needs full block context (code
            // fences, tables) — buffer until commit even when the
            // prompt-gate would normally allow inline raw streaming.
            // The user gets the rendered turn as one block, no live
            // char-by-char, which is the documented trade-off.
            if (main && promptGate.isExclusive() && !markdownState.isEnabled()) {
                if (!state.headerEmitted) {
                    terminal.streamRaw(header(state.processName, state.role));
                    state.headerEmitted = true;
                }
                terminal.streamRaw(chunk);
                // Also accumulate so the assembled line can be mirrored
                // into the scrollback buffer at commit — chunks via
                // streamRaw bypass record(), which would otherwise leave
                // streamed assistant turns invisible to /debug/output.
                state.buffered.append(chunk);
            } else {
                // Either the prompt is active (must not write raw) or
                // this is a worker (no inline streaming). Buffer.
                state.buffered.append(chunk);
            }
        }
    }

    private boolean isMainProcess(@Nullable String processName) {
        if (processName == null) return false;
        return Objects.equals(processName, sessions.activeProcess());
    }

    /**
     * Terminates any in-flight exclusive-mode stream with a newline so
     * a subsequent {@link ChatTerminal#printlnStyled} (or any other
     * {@code printAbove}-based write) doesn't land on the same line.
     * Resets {@code headerEmitted} so the next chunk for that process
     * re-emits the role header on a fresh line.
     *
     * <p>Buffered (non-exclusive) streams are untouched — they don't
     * write to the terminal until {@link #onCommit}, so there's no
     * conflict.
     *
     * <p>Called by side-channel renderers (status / plan / metrics)
     * just before they print, so the chat stream and the progress
     * pings don't visually fuse.
     */
    public void suspend() {
        for (ProcessStream state : streams.values()) {
            synchronized (state) {
                if (state.headerEmitted) {
                    terminal.streamRaw("\n");
                    state.headerEmitted = false;
                }
            }
        }
    }

    /**
     * Closes the stream for a process. Returns {@code true} when this
     * call rendered the assistant's content already — caller should
     * suppress the canonical commit render to avoid duplication.
     */
    public boolean onCommit(String processId) {
        if (processId == null) return false;
        ProcessStream state = streams.remove(processId);
        if (state == null) return false;
        synchronized (state) {
            if (state.headerEmitted) {
                // Streamed inline while exclusive — close the line.
                terminal.streamRaw("\n");
                // Mirror the assembled line into the scrollback so
                // /debug/output reflects what the user just saw.
                if (state.buffered.length() > 0) {
                    terminal.recordChat(
                            header(state.processName, state.role) + state.buffered);
                }
                return true;
            }
            if (state.buffered.length() > 0) {
                // Buffered stream — flush via printAbove so the prompt
                // redraws cleanly below. Main-process replies go
                // through the markdown-aware renderer (header on its
                // own line, content rendered or raw based on the
                // toggle); worker replies stay in the green
                // side-channel and are truncated as an audit trail.
                String head = header(state.processName, state.role);
                String body = state.buffered.toString();
                if (isMainProcess(state.processName)) {
                    terminal.chatMarkdown(head, body);
                } else {
                    terminal.worker(head + body);
                }
                return true;
            }
            return false;
        }
    }

    /**
     * Flushes any buffered chat content for a process <em>mid-turn</em>
     * without closing the stream. Called just before a tool line lands
     * (see {@code ProcessProgressHandler}) so the narration that
     * preceded a tool call interleaves with it, instead of the whole
     * turn's prose piling up in one block at {@link #onCommit} (markdown
     * mode buffers per-turn, and an action loop spans many tool
     * iterations).
     *
     * <p>No-op for the inline exclusive path — that content already
     * reached the terminal live; {@link #suspend()} handles its newline.
     * The {@link ProcessStream} state survives so later chunks accumulate
     * a fresh segment; a subsequent flush / commit renders that.
     */
    public void flushBuffered(String processId) {
        if (processId == null) return;
        ProcessStream state = streams.get(processId);
        if (state == null) return;
        synchronized (state) {
            if (state.headerEmitted || state.buffered.length() == 0) {
                // Inline stream (handled by suspend) or nothing pending.
                return;
            }
            String head = header(state.processName, state.role);
            String body = state.buffered.toString();
            if (isMainProcess(state.processName)) {
                terminal.chatMarkdown(head, body);
            } else {
                terminal.worker(head + body);
            }
            state.buffered.setLength(0);
        }
    }

    private static String header(@Nullable String processName, @Nullable ChatRole role) {
        String name = processName == null ? "?" : processName;
        String roleStr = role == null ? "?" : role.name().toLowerCase();
        return "[" + name + " · " + roleStr + "] ";
    }

    /** Per-process accumulation state. */
    private static final class ProcessStream {
        @Nullable String processName;
        @Nullable ChatRole role;
        boolean headerEmitted = false;
        final StringBuilder buffered = new StringBuilder();

        ProcessStream(@Nullable String processName, @Nullable ChatRole role) {
            this.processName = processName;
            this.role = role;
        }
    }

    /** Per-process reasoning-stream accumulation state. */
    private static final class ThinkingStream {
        boolean headerEmitted = false;
        boolean streamedLive = false;
        final StringBuilder lineBuf = new StringBuilder();
    }
}
