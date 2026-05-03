package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.StreamingDisplay;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code chat-message-appended} notifications — the canonical
 * commit of a chat turn.
 *
 * <p>If the matching turn was streamed via
 * {@code chat-message-stream-chunk}, {@link StreamingDisplay#onCommit}
 * closes the streaming line with a newline and reports {@code true};
 * we then suppress the canonical render to avoid showing the same
 * text twice. For messages that didn't stream (user input echoes,
 * system notes, clients that don't support streaming) the canonical
 * is rendered as before.
 */
@Component
public class ChatMessageAppendedHandler implements MessageHandler {

    private final ChatTerminal terminal;
    private final StreamingDisplay streaming;
    private final SessionService sessions;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ChatMessageAppendedHandler(ChatTerminal terminal,
                                      StreamingDisplay streaming,
                                      SessionService sessions) {
        this.terminal = terminal;
        this.streaming = streaming;
        this.sessions = sessions;
    }

    @Override
    public String messageType() {
        return MessageType.CHAT_MESSAGE_APPENDED;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        ChatMessageAppendedData data = json.convertValue(
                envelope.getData(), ChatMessageAppendedData.class);
        boolean wasStreamed = streaming.onCommit(data.getThinkProcessId());
        if (wasStreamed) {
            return;
        }
        String role = data.getRole() == null ? "?" : data.getRole().name().toLowerCase();
        String line = "[" + data.getProcessName() + " · " + role + "] " + data.getContent();

        // Only the bound main process (Arthur) writes into the main
        // chat — that's the user-facing conversation. Workers run
        // under the hood; their chat-messages are audit material
        // that the orchestrator reads via {@code <process-event>}
        // markers and surfaces via {@code RELAY}. Showing them in
        // the main chat leads to dual-voice confusion ("[arthur]
        // ..." right next to "[web-research-xxx] ...") and
        // double-rendering with a subsequent RELAY. Worker rows go
        // to the dimmed side-channel as a transparent audit trail —
        // the user can see what the workers are doing, but not as
        // primary conversation content.
        if (isMainProcess(data.getProcessName())) {
            terminal.chat(line);
        } else {
            terminal.worker(line);
        }
    }

    private boolean isMainProcess(@org.jspecify.annotations.Nullable String processName) {
        if (processName == null) return false;
        return Objects.equals(processName, sessions.activeProcess());
    }
}
