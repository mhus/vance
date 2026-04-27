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
public class InboxItemDto {

    private String id;
    private String tenantId;

    private String originatorUserId;
    private String assignedToUserId;
    private @Nullable String originProcessId;
    private @Nullable String originSessionId;

    private InboxItemType type;
    private Criticality criticality;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private String title;
    private @Nullable String body;
    private @Nullable Map<String, Object> payload;

    private InboxItemStatus status;
    private boolean requiresAction;
    private @Nullable AnswerPayload answer;
    private @Nullable ResolvedBy resolvedBy;
    private @Nullable Instant resolvedAt;
    private @Nullable String resolverReason;

    private @Nullable Instant createdAt;
    private @Nullable Instant updatedAt;
    private @Nullable Instant archivedAt;
}
