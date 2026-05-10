package de.mhus.vance.shared.chat;

import de.mhus.vance.api.chat.ChatRole;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent chat message.
 *
 * <p>{@code sessionId} is the {@code SessionDocument.sessionId} (business
 * id); {@code thinkProcessId} is the Mongo id of the owning
 * {@code ThinkProcessDocument}. The combined index
 * {@code (tenantId, sessionId, thinkProcessId, createdAt)} supports the
 * hot-path query: "give me the history of process X in order".
 *
 * <p>{@code tags} hold marker annotations set by the tool dispatcher
 * and engines (e.g. {@code FILE_EDIT}, {@code TOOL_CALL:client_file_edit},
 * {@code RESOURCE:CLIENT_FILE:/abs/path}, {@code PLAN_STEP_STARTED:cleanup}).
 * They drive the {@code history_search} tool that lets the LLM look up
 * past turns — including ones already rolled into a compaction memory.
 * See {@code planning/process-history-search.md}.
 */
@Document(collection = "chat_messages")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_session_process_time_idx",
                def = "{ 'tenantId': 1, 'sessionId': 1, 'thinkProcessId': 1, 'createdAt': 1 }"),
        @CompoundIndex(
                name = "tenant_process_tags_time_idx",
                def = "{ 'tenantId': 1, 'thinkProcessId': 1, 'tags': 1, 'createdAt': -1 }")
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

    @TextIndexed
    private String content = "";

    /**
     * Set when the message has been rolled into a memory compaction
     * ({@code MemoryDocument.id}). Replay paths skip these so the LLM
     * sees the compacted summary instead of the originals; the
     * originals stay in Mongo, audit-readable.
     */
    private @Nullable String archivedInMemoryId;

    /**
     * Marker tags for LLM-accessible history search. Append-only —
     * engines/tools add typed markers for classification, never remove.
     * Convention: {@code TYPE} or {@code TYPE:VALUE}. Examples:
     * {@code TOOL_CALL:client_file_edit}, {@code RESOURCE:CLIENT_FILE:/abs/path},
     * {@code FILE_EDIT}, {@code PLAN_STEP_STARTED:cleanup}, {@code ERROR}.
     *
     * <p>{@link LinkedHashSet} for stable iteration order in debug
     * output; the performance difference vs. {@link java.util.HashSet}
     * is irrelevant at this size.
     */
    @Builder.Default
    private Set<String> tags = new LinkedHashSet<>();

    @CreatedDate
    private @Nullable Instant createdAt;
}
