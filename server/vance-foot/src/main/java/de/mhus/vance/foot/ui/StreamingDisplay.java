package de.mhus.vance.foot.ui;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.foot.session.SessionService;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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

    private final ChatTerminal terminal;
    private final PromptGate promptGate;
    private final SessionService sessions;
    private final Map<String, ProcessStream> streams = new ConcurrentHashMap<>();

    public StreamingDisplay(ChatTerminal terminal,
                            PromptGate promptGate,
                            SessionService sessions) {
        this.terminal = terminal;
        this.promptGate = promptGate;
        this.sessions = sessions;
    }

    /** Append a delta to the per-process stream. */
    public void onChunk(
            String processId,
            @Nullable String processName,
            @Nullable ChatRole role,
            String chunk) {
        if (processId == null || chunk == null || chunk.isEmpty()) return;
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
            if (main && promptGate.isExclusive()) {
                if (!state.headerEmitted) {
                    terminal.streamRaw(header(state.processName, state.role));
                    state.headerEmitted = true;
                }
                terminal.streamRaw(chunk);
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
                return true;
            }
            if (state.buffered.length() > 0) {
                // Buffered stream — flush via printAbove so the prompt
                // redraws cleanly below. Main-process replies render
                // in default chat (white, full); worker replies render
                // in green and truncated as a side-channel audit
                // trail — Arthur's RELAY pulls their content into the
                // main chat as needed.
                String line = header(state.processName, state.role)
                        + state.buffered;
                if (isMainProcess(state.processName)) {
                    terminal.chat(line);
                } else {
                    terminal.worker(line);
                }
                return true;
            }
            return false;
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
}
