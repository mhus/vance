package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.StreamingDisplay;
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
    private final ObjectMapper json = JsonMapper.builder().build();

    public ChatMessageAppendedHandler(ChatTerminal terminal, StreamingDisplay streaming) {
        this.terminal = terminal;
        this.streaming = streaming;
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
        terminal.info("[" + data.getProcessName() + " · " + role + "] " + data.getContent());
    }
}
