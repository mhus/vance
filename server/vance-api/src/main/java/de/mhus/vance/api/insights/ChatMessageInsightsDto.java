package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.chat.ChatRole;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of one chat message for the insights inspector.
 *
 * <p>{@link #archivedInMemoryId} flags messages that have been
 * compacted into a memory entry — the UI shows them dimmed and links
 * to the memory.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class ChatMessageInsightsDto {

    private String id;

    private ChatRole role;

    private String content;

    private @Nullable String archivedInMemoryId;

    private @Nullable Instant createdAt;
}
