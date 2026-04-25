package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code chat-message-appended} notifications — the canonical commit
 * of a chat turn. The Brain emits this after the LLM finishes for one process
 * turn; clients should treat any prior {@code chat-message-stream-chunk}s as
 * superseded.
 */
@Component
public class ChatMessageAppendedHandler implements MessageHandler {

    private final ChatTerminal terminal;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ChatMessageAppendedHandler(ChatTerminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public String messageType() {
        return MessageType.CHAT_MESSAGE_APPENDED;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        ChatMessageAppendedData data = json.convertValue(envelope.getData(), ChatMessageAppendedData.class);
        String role = data.getRole() == null ? "?" : data.getRole().name().toLowerCase();
        terminal.info("[" + data.getProcessName() + " · " + role + "] " + data.getContent());
    }
}
