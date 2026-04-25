package de.mhus.vance.foot.ui;

import de.mhus.vance.api.chat.ChatRole;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Renders chat-streaming chunks directly into the terminal scrollback,
 * delta-by-delta — the assistant's reply grows in place where it will
 * eventually live, with no preview-vs-canonical duality.
 *
 * <p>Two events drive it:
 * <ul>
 *   <li>{@link #onChunk} — write the delta. The first chunk for a
 *       process emits the header prefix; subsequent chunks just
 *       append the delta. No newline mid-stream.</li>
 *   <li>{@link #onCommit} — terminate the line with a newline (if any
 *       chunks were seen for this process) and forget the per-process
 *       state. Returns whether anything was streamed, so the caller
 *       (the {@code chat-message-appended} handler) can decide
 *       whether to also emit the canonical full text — clients that
 *       already streamed skip it; non-streaming clients emit it.</li>
 * </ul>
 *
 * <p>Direct terminal writes are only safe between {@code readLine}
 * iterations. The current REPL holds the read between user-Enter and
 * the steer-reply, and the brain ships chunks + commit before the
 * reply, so the window is exactly where it needs to be. If we ever
 * stream while the prompt is active, this needs LineReader-aware
 * coordination.
 */
@Component
public class StreamingDisplay {

    private final ChatTerminal terminal;
    private final Map<String, ProcessStream> streams = new ConcurrentHashMap<>();

    public StreamingDisplay(ChatTerminal terminal) {
        this.terminal = terminal;
    }

    /** Append a delta to the per-process scrollback line. */
    public void onChunk(String processId, @Nullable String processName, ChatRole role, String chunk) {
        if (processId == null || chunk == null || chunk.isEmpty()) return;
        ProcessStream state = streams.computeIfAbsent(
                processId, k -> new ProcessStream());
        synchronized (state) {
            if (!state.headerEmitted) {
                terminal.streamRaw(header(processName, role));
                state.headerEmitted = true;
            }
            terminal.streamRaw(chunk);
        }
    }

    /**
     * Closes the streaming line for a process. Returns {@code true} if
     * any chunk was streamed for this process — caller should suppress
     * its canonical render in that case to avoid duplication.
     */
    public boolean onCommit(String processId) {
        if (processId == null) return false;
        ProcessStream state = streams.remove(processId);
        if (state == null) return false;
        synchronized (state) {
            if (state.headerEmitted) {
                terminal.streamRaw("\n");
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

    /** Per-process scrollback line state. */
    private static final class ProcessStream {
        boolean headerEmitted = false;
    }
}
