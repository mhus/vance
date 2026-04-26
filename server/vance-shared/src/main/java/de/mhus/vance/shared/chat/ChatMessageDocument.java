package de.mhus.vance.shared.chat;

import de.mhus.vance.api.chat.ChatRole;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent chat message.
 *
 * <p>{@code sessionId} is the {@code SessionDocument.sessionId} (business
 * id); {@code thinkProcessId} is the Mongo id of the owning
 * {@code ThinkProcessDocument}. The combined index
 * {@code (tenantId, sessionId, thinkProcessId, createdAt)} supports the
 * hot-path query: "give me the history of process X in order".
 */
@Document(collection = "chat_messages")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_session_process_time_idx",
                def = "{ 'tenantId': 1, 'sessionId': 1, 'thinkProcessId': 1, 'createdAt': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String sessionId = "";

    /** Owning think-process — {@code ThinkProcessDocument.id} (Mongo id). */
    private String thinkProcessId = "";

    private ChatRole role = ChatRole.USER;

    private String content = "";

    /**
     * Set when the message has been rolled into a memory compaction
     * ({@code MemoryDocument.id}). Replay paths skip these so the LLM
     * sees the compacted summary instead of the originals; the
     * originals stay in Mongo, audit-readable.
     */
    private @Nullable String archivedInMemoryId;

    @CreatedDate
    private @Nullable Instant createdAt;
}
