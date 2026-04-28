package de.mhus.vance.foot.command;

import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.BrainException;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.PromptGate;
import java.time.Duration;
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

    private final CommandService commandService;
    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal chatTerminal;
    private final PromptGate promptGate;

    public ChatInputService(CommandService commandService,
                            ConnectionService connection,
                            SessionService sessions,
                            ChatTerminal chatTerminal,
                            PromptGate promptGate) {
        this.commandService = commandService;
        this.connection = connection;
        this.sessions = sessions;
        this.chatTerminal = chatTerminal;
        this.promptGate = promptGate;
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
