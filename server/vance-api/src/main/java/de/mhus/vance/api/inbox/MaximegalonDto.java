package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Wire-format projection of a persistent inbox item — used in
 * {@code inbox-list}, {@code inbox-item}, and {@code inbox-item-added}
 * frames. {@link #payload} is the type-specific question shape;
 * {@link #answer} is filled when the item has been resolved.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class MaximegalonDto {

    private String id;
    private String tenantId;

    private String originatorUserId;
    private String assignedToUserId;
    private @Nullable String originProcessId;
    private @Nullable String originSessionId;

    private MaximegalonType type;
    private Criticality criticality;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private String title;
    private @Nullable String body;
    private @Nullable Map<String, Object> payload;

    /**
     * Set when answering this item executes something on the server
     * (e.g. a permission change). Clients use its presence to fetch the
     * server-rendered facts from {@code GET .../inbox/{id}/effect} and to
     * show that the decision has consequences beyond a reply.
     */
    private @Nullable String effectType;

    private MaximegalonStatus status;
    private boolean requiresAction;
    private @Nullable AnswerPayload answer;
    private @Nullable ResolvedBy resolvedBy;
    private @Nullable Instant resolvedAt;
    private @Nullable String resolverReason;

    private @Nullable Instant createdAt;
    private @Nullable Instant updatedAt;
    private @Nullable Instant archivedAt;

    // ── Thread ──

    /**
     * Team that may look on without being a participant, or {@code null} when
     * visibility is derived from the assignee's teams (the historical rule).
     */
    private @Nullable String teamId;

    /** Who receives updates and may contribute. */
    @Builder.Default
    private List<String> participants = new ArrayList<>();

    /** Who has read title and body — the thread's own read state. */
    @Builder.Default
    private List<String> readBy = new ArrayList<>();

    /**
     * The clarification, flat with {@code parentId} links; the client builds
     * the tree.
     *
     * <p><b>Absent in list responses.</b> A listing needs titles, not
     * transcripts, and the messages live in the same document — so the list
     * query projects them out. A client that has an item from the list and
     * wants the discussion fetches the single item.
     */
    @Builder.Default
    private List<MaximegalonMessageDto> messages = new ArrayList<>();

    /** Reactions on the thread's own title and body. */
    @Builder.Default
    private List<MaximegalonReactionDto> reactions = new ArrayList<>();
}
