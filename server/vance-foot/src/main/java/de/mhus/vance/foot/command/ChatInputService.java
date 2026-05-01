package de.mhus.vance.foot.command;

import de.mhus.vance.api.thinkprocess.ProcessPauseRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.thinkprocess.ProcessStopRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.BrainException;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.BusyIndicator;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.PromptGate;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Single entry point for "user input" — the line either starts with a slash
 * (slash command, dispatched through {@link CommandService}) or it is chat
 * content steered to the active think-process via {@link ConnectionService}.
 *
 * <p>Both the JLine REPL ({@code ChatRepl}) and the debug REST endpoint
 * ({@code DebugRestServer}) call into this service so the two surfaces stay
 * structurally identical — every remote-control automation gets the exact
 * same behavior the human user sees in the REPL.
 *
 * <p>The {@link PromptGate} is flipped to exclusive while the input is being
 * processed, so async streaming sinks can write directly to the terminal
 * without corrupting an active prompt.
 */
@Service
public class ChatInputService {

    /** Default timeout for the chat round-trip to the brain. */
    public static final Duration DEFAULT_CHAT_TIMEOUT = Duration.ofSeconds(120);

    /** Timeout for fire-and-forget pause requests. Short — pause is a side-channel. */
    public static final Duration PAUSE_TIMEOUT = Duration.ofSeconds(10);

    private final CommandService commandService;
    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal chatTerminal;
    private final PromptGate promptGate;
    private final BusyIndicator busyIndicator;

    /**
     * Background executor for async chat submission. Keeps the REPL
     * responsive while the brain is processing — critical for ESC-stop
     * to be interceptable while a chat-process is "thinking".
     */
    private final ExecutorService asyncExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "chat-async-submit");
                t.setDaemon(true);
                return t;
            });

    public ChatInputService(CommandService commandService,
                            ConnectionService connection,
                            SessionService sessions,
                            ChatTerminal chatTerminal,
                            PromptGate promptGate,
                            BusyIndicator busyIndicator) {
        this.commandService = commandService;
        this.connection = connection;
        this.sessions = sessions;
        this.chatTerminal = chatTerminal;
        this.promptGate = promptGate;
        this.busyIndicator = busyIndicator;
    }

    /**
     * Submits a raw input line. If it starts with {@code /} it is dispatched
     * as a slash command; otherwise it is treated as chat input. Mirrors the
     * REPL behavior exactly.
     */
    public InputResult submit(String line) {
        if (line == null) {
            return InputResult.command("", false, "blank input");
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return InputResult.command("", false, "blank input");
        }
        promptGate.enterExclusive();
        try {
            if (trimmed.startsWith("/")) {
                boolean matched = commandService.execute(trimmed);
                return InputResult.command(trimmed, matched, null);
            }
            return sendChatLocked(trimmed, DEFAULT_CHAT_TIMEOUT);
        } finally {
            promptGate.exitExclusive();
        }
    }

    /**
     * REPL variant of {@link #submit}: dispatches commands synchronously
     * (they're cheap), but routes chat content through the async
     * executor so the REPL can return to {@code readLine} immediately.
     * That keeps ESC-stop interceptable while the chat-process is
     * "thinking" — without the async path the REPL thread is blocked
     * inside {@code connection.request} for the duration of the engine
     * turn and {@code readLine} can't fire its key bindings.
     *
     * <p>Streaming output (chat-message-appended, process-progress) is
     * unaffected — those notifications already render via
     * {@code printAbove}-based writes that respect the prompt gate.
     */
    public void submitFromRepl(String line) {
        if (line == null) return;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;
        if (trimmed.startsWith("/")) {
            // Slash commands stay synchronous — they're cheap and the
            // REPL expects their feedback before the next prompt.
            submit(line);
            return;
        }
        // Chat content → async dispatch so the REPL is free to capture ESC.
        asyncExecutor.submit(() -> submit(line));
    }

    /**
     * Fire-and-forget {@code process-pause} for everything active in
     * the bound session (chat + workers). Halts further turns; the
     * user's next typed chat message lets Arthur decide what to do
     * (resume + steer, or stop + create fresh).
     *
     * <p><b>Bypasses the chat asyncExecutor on purpose.</b> The
     * single-thread executor that handles user-typed chat content
     * may be blocked inside a synchronous {@code process-steer}
     * round-trip (waiting for the brain's reply). Queueing the pause
     * behind that would mean the pause arrives only after the very
     * turn the user wanted to interrupt has finished. We therefore
     * call {@link de.mhus.vance.foot.connection.ConnectionService#send}
     * directly from the caller thread (typically the JLine ESC widget
     * or the slash-dispatch thread) — non-blocking, fires immediately.
     * The brain's {@code ENGINE_HALT_REQUESTED} progress ping arrives
     * back as user feedback through the existing notification channel.
     */
    public void requestPause() {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            return;
        }
        boolean sent = connection.send(de.mhus.vance.api.ws.WebSocketEnvelope.request(
                "pause_" + System.nanoTime(),
                MessageType.PROCESS_PAUSE,
                ProcessPauseRequest.builder().build()));
        if (!sent) {
            chatTerminal.error("pause failed: not connected");
            return;
        }
        // Drop the busy spinner immediately. The pending chat-request
        // (PROCESS_STEER) is still waiting for its reply on the
        // asyncExecutor — but from the user's POV, /pause means "I'm
        // done waiting". When the steer reply eventually arrives, the
        // exit() in sendChatLocked's finally is a no-op (caps at 0).
        busyIndicator.clear();
    }

    /**
     * Fire-and-forget {@code process-stop} broadcast for the active
     * workers in the bound session. Symmetric to {@link #requestPause()}
     * but harder: workers go to {@code CLOSED} ({@code closeReason=STOPPED})
     * instead of {@code PAUSED}. Arthur sees the resulting STOPPED
     * parent-notifications and can decide whether to spawn fresh.
     *
     * <p>Same async-bypass rationale as {@link #requestPause()} —
     * goes directly through {@code connection.send} instead of the
     * chat asyncExecutor so it isn't blocked behind a long-running
     * chat round-trip.
     */
    public void requestStop() {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            return;
        }
        boolean sent = connection.send(de.mhus.vance.api.ws.WebSocketEnvelope.request(
                "stop_" + System.nanoTime(),
                MessageType.PROCESS_STOP,
                ProcessStopRequest.builder().build()));
        if (!sent) {
            chatTerminal.error("stop failed: not connected");
            return;
        }
        // Same UX rationale as requestPause(): clear the spinner now,
        // don't keep the user staring at an animation while the
        // already-in-flight steer reply trickles in.
        busyIndicator.clear();
    }

    @PreDestroy
    void shutdown() {
        asyncExecutor.shutdown();
    }

    /**
     * Sends {@code line} as a chat message regardless of leading slash —
     * skips the command dispatcher entirely. Used by {@code POST /debug/chat}
     * so a remote driver can post content like {@code "/usr/bin/ls"} as chat
     * without it being misrouted into the slash dispatcher.
     */
    public InputResult sendChat(String line, Duration timeout) {
        if (line == null || line.isEmpty()) {
            return InputResult.chat("", false, "blank input");
        }
        promptGate.enterExclusive();
        try {
            return sendChatLocked(line, timeout);
        } finally {
            promptGate.exitExclusive();
        }
    }

    private InputResult sendChatLocked(String line, Duration timeout) {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            String msg = "No bound session — /connect, then /session-resume or /session-create.";
            chatTerminal.error(msg);
            return InputResult.chat(line, false, msg);
        }
        String process = sessions.activeProcess();
        if (process == null) {
            String msg = "No active process — /process <name> first, "
                    + "or use /process-steer <name> <message>.";
            chatTerminal.error(msg);
            return InputResult.chat(line, false, msg);
        }
        // Mark busy *around* the synchronous brain round-trip — the
        // status-bar animation polls this flag and shows the user
        // that something is in flight even while the REPL prompt is
        // back and waiting for input.
        busyIndicator.enter();
        try {
            ProcessSteerResponse response = connection.request(
                    MessageType.PROCESS_STEER,
                    ProcessSteerRequest.builder()
                            .processName(process)
                            .content(line)
                            .build(),
                    ProcessSteerResponse.class,
                    timeout);
            chatTerminal.verbose("→ steered " + response.getProcessName()
                    + " (status=" + response.getStatus() + ")");
            return InputResult.chat(line, true, null);
        } catch (BrainException e) {
            chatTerminal.error(e.getMessage());
            return InputResult.chat(line, false, e.getMessage());
        } catch (Exception e) {
            String msg = "Steer failed: " + e.getMessage();
            chatTerminal.error(msg);
            return InputResult.chat(line, false, msg);
        } finally {
            busyIndicator.exit();
        }
    }

    /** Discriminator for which path {@link #submit(String)} took. */
    public enum InputKind { COMMAND, CHAT }

    /**
     * Result of a {@link #submit(String)} or {@link #sendChat(String, Duration)}
     * call. {@code ok} carries the routed-and-handled-cleanly bit:
     * <ul>
     *   <li>{@code COMMAND} — {@code true} if a slash command matched,
     *       {@code false} for unknown / blank.</li>
     *   <li>{@code CHAT} — {@code true} if the brain acknowledged the
     *       {@code PROCESS_STEER}, {@code false} on missing session/process
     *       or any error talking to the brain.</li>
     * </ul>
     * {@code error} is {@code null} on success and otherwise the human-
     * readable reason already shown on the terminal.
     */
    public record InputResult(InputKind kind, String line, boolean ok, @Nullable String error) {
        static InputResult command(String line, boolean matched, @Nullable String error) {
            return new InputResult(InputKind.COMMAND, line, matched, error);
        }

        static InputResult chat(String line, boolean delivered, @Nullable String error) {
            return new InputResult(InputKind.CHAT, line, delivered, error);
        }
    }
}
