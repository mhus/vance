package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.StreamingDisplay;
import de.mhus.vance.foot.ui.Verbosity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives {@code chat-message-thinking-chunk} notifications and streams
 * the assistant's reasoning ("thoughts") live via {@link StreamingDisplay}
 * as a dimmed side-channel. Reasoning arrives before the answer; the
 * answer's {@link MessageType#CHAT_MESSAGE_STREAM_CHUNK} chunks then close
 * the reasoning line. The canonical {@link MessageType#CHAT_MESSAGE_APPENDED}
 * still carries the full reasoning; the appended-handler suppresses the
 * end-of-turn thoughts block when it was already streamed here.
 */
@Component
public class ChatMessageThinkingChunkHandler implements MessageHandler {

    private final StreamingDisplay streaming;
    private final ChatTerminal terminal;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ChatMessageThinkingChunkHandler(StreamingDisplay streaming, ChatTerminal terminal) {
        this.streaming = streaming;
        this.terminal = terminal;
    }

    @Override
    public String messageType() {
        return MessageType.CHAT_MESSAGE_THINKING_CHUNK;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        ChatMessageChunkData data = json.convertValue(
                envelope.getData(), ChatMessageChunkData.class);
        if (data == null || data.getThinkProcessId() == null) {
            return;
        }
        if (terminal.threshold().shows(Verbosity.DEBUG)) {
            terminal.println(Verbosity.DEBUG,
                    "thinking-chunk[%s]: %s",
                    data.getProcessName(),
                    data.getChunk());
        }
        streaming.onThinkingChunk(
                data.getThinkProcessId(),
                data.getProcessName(),
                data.getRole(),
                data.getChunk() == null ? "" : data.getChunk());
    }
}
