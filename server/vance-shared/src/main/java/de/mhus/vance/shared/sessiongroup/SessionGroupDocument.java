package de.mhus.vance.shared.sessiongroup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Persistent session-group record, scoped to {@code (tenant, project, user)}.
 *
 * <p>{@code tenantId} references {@code TenantDocument.name}, {@code projectId}
 * references {@code ProjectDocument.name}, {@code userId} references the owning
 * user. {@code name} is the unique business identifier inside that scope.
 *
 * <p>{@code sessionIds} holds {@code SessionDocument.sessionId} values and is
 * pure membership — the list order is not significant (sessions render in the
 * usual {@code pinned + lastActivityAt} order inside a group). Groups
 * themselves order by {@code sortIndex}.
 */
@Document(collection = "session_groups")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_project_user_name_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'userId': 1, 'name': 1 }",
                unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionGroupDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String projectId = "";

    private String userId = "";

    private String name = "";

    private @Nullable String title;

    private int sortIndex;

    @Builder.Default
    private List<String> sessionIds = new ArrayList<>();

    @CreatedDate
    private @Nullable Instant createdAt;
}
