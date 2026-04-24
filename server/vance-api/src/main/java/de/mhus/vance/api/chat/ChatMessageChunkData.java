package de.mhus.vance.api.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload of the {@code chat-message-stream-chunk} server notification.
 *
 * <p>Carries a progressive fragment of an assistant reply that has not
 * yet been committed to the chat log. The server batches partial
 * tokens from the LLM and emits one chunk at a time (size threshold or
 * flush timer, whichever fires first). Clients render the chunks
 * optimistically — when {@code chat-message-appended} arrives for the
 * same {@code thinkProcessId}, the canonical text supersedes all
 * chunks accumulated for that turn.
 *
 * <p>No {@code chatMessageId} yet: the message doesn't exist in the
 * log until the turn completes and {@code append} persists it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("chat")
public class ChatMessageChunkData {

    private String thinkProcessId;

    private String processName;

    private ChatRole role;

    /** The delta — not the full message so far. Clients concatenate. */
    private String chunk;
}
