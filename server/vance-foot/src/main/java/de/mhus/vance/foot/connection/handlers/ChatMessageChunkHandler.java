package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.Verbosity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives {@code chat-message-stream-chunk} notifications. For now we only
 * surface them at {@code DEBUG} — the JLine cursor management for live,
 * progressive rendering will land once the chat-streaming UX is designed.
 *
 * <p>The authoritative content arrives as {@code chat-message-appended}; the
 * user sees no gap if we silently drop chunks below {@code DEBUG}.
 */
@Component
public class ChatMessageChunkHandler implements MessageHandler {

    private final ChatTerminal terminal;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ChatMessageChunkHandler(ChatTerminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public String messageType() {
        return MessageType.CHAT_MESSAGE_STREAM_CHUNK;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        if (!terminal.threshold().shows(Verbosity.DEBUG)) {
            return;
        }
        ChatMessageChunkData data = json.convertValue(envelope.getData(), ChatMessageChunkData.class);
        terminal.println(Verbosity.DEBUG,
                "chunk[%s/%s]: %s",
                data.getProcessName(),
                data.getRole() == null ? "?" : data.getRole().name().toLowerCase(),
                data.getChunk());
    }
}
