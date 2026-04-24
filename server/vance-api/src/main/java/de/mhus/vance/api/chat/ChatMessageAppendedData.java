package de.mhus.vance.api.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of the {@code chat-message-appended} server notification.
 *
 * <p>Fires every time a {@code ChatMessageDocument} is persisted for a
 * process the connected client owns. The client renders the message in its
 * UI — user echoes, assistant replies, system notes all arrive through
 * this same channel.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("chat")
public class ChatMessageAppendedData {

    private String chatMessageId;

    private String thinkProcessId;

    private String processName;

    private ChatRole role;

    private String content;

    private @Nullable Instant createdAt;
}
