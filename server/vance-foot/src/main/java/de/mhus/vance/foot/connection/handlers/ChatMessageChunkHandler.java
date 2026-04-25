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
 * Receives {@code chat-message-stream-chunk} notifications and delegates
 * to {@link StreamingDisplay}, which appends each delta directly into the
 * terminal scrollback. The canonical commit arrives as
 * {@link MessageType#CHAT_MESSAGE_APPENDED}; the appended-handler closes
 * the streaming line there.
 *
 * <p>At {@link Verbosity#DEBUG} every chunk also produces a trace line so
 * the wire is observable without disrupting the streaming render.
 */
@Component
public class ChatMessageChunkHandler implements MessageHandler {

    private final ChatTerminal terminal;
    private final StreamingDisplay streaming;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ChatMessageChunkHandler(ChatTerminal terminal, StreamingDisplay streaming) {
        this.terminal = terminal;
        this.streaming = streaming;
    }

    @Override
    public String messageType() {
        return MessageType.CHAT_MESSAGE_STREAM_CHUNK;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        ChatMessageChunkData data = json.convertValue(
                envelope.getData(), ChatMessageChunkData.class);
        if (data == null || data.getThinkProcessId() == null) {
            return;
        }
        streaming.onChunk(
                data.getThinkProcessId(),
                data.getProcessName(),
                data.getRole(),
                data.getChunk() == null ? "" : data.getChunk());
        if (terminal.threshold().shows(Verbosity.DEBUG)) {
            terminal.println(Verbosity.DEBUG,
                    "chunk[%s/%s]: %s",
                    data.getProcessName(),
                    data.getRole() == null ? "?" : data.getRole().name().toLowerCase(),
                    data.getChunk());
        }
    }
}
