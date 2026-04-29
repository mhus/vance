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
 * Snapshot of a single persisted chat message for the chat-history REST
 * endpoint ({@code GET /brain/{tenant}/sessions/{id}/messages}).
 *
 * <p>Distinct from {@link ChatMessageAppendedData}, which is the push
 * notification fired when a message is persisted: this DTO is the pull
 * result for loading history on chat-editor mount.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("chat")
public class ChatMessageDto {

    private String messageId;

    private String thinkProcessId;

    private ChatRole role;

    private String content;

    private @Nullable Instant createdAt;
}
