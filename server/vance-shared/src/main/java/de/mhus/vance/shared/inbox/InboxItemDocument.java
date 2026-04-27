package de.mhus.vance.shared.inbox;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemStatus;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.inbox.ResolvedBy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent inbox item — answers AND outputs ({@link InboxItemType}).
 * See {@code specification/user-interaction.md} §3 for the full shape.
 */
@Document(collection = "inbox_items")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_assigned_status_crit_idx",
                def = "{ 'tenantId': 1, 'assignedToUserId': 1, 'status': 1, 'criticality': 1 }"),
        @CompoundIndex(
                name = "tenant_session_status_idx",
                def = "{ 'tenantId': 1, 'originSessionId': 1, 'status': 1 }"),
        @CompoundIndex(
                name = "process_status_idx",
                def = "{ 'originProcessId': 1, 'status': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxItemDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** Who created it (immutable, audit). */
    private String originatorUserId = "";

    /** Who's currently assigned (mutable via delegation). */
    private String assignedToUserId = "";

    /** Originating process Mongo-id; {@code null} when no process is
     *  blocked on this item (pure tool-driven posting). */
    private @Nullable String originProcessId;

    /** Originating session-id (business id), for session-level filtering. */
    private @Nullable String originSessionId;

    private InboxItemType type = InboxItemType.OUTPUT_TEXT;

    @Builder.Default
    private Criticality criticality = Criticality.NORMAL;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private String title = "";

    /** Markdown body — long-form description / prompt text. */
    private @Nullable String body;

    /** Type-specific structured data (options for DECISION, schema
     *  for STRUCTURE_EDIT, url for OUTPUT_IMAGE, etc.). */
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Builder.Default
    private InboxItemStatus status = InboxItemStatus.PENDING;

    /** {@code true} when the originating process expects an answer
     *  (asks). {@code false} for pure outputs. */
    private boolean requiresAction;

    /** Set when {@link #status} is {@code ANSWERED} or {@code DISMISSED}. */
    private @Nullable AnswerPayload answer;

    private @Nullable ResolvedBy resolvedBy;
    private @Nullable Instant resolvedAt;
    private @Nullable String resolverReason;

    @Builder.Default
    private List<InboxItemHistoryEntry> history = new ArrayList<>();

    @Version
    private @Nullable Long version;

    @CreatedDate
    private @Nullable Instant createdAt;

    @LastModifiedDate
    private @Nullable Instant updatedAt;

    private @Nullable Instant archivedAt;
}
