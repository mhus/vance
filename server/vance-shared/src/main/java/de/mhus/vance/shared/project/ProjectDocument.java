package de.mhus.vance.shared.project;

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
 * Persistent project record, scoped to a tenant and optionally to a project
 * group.
 *
 * <p>{@code tenantId} references {@code TenantDocument.name};
 * {@code projectGroupId} (nullable) references
 * {@code ProjectGroupDocument.name}; {@code teamIds} reference
 * {@code TeamDocument.name}. Look-ups always use {@code name}, never the
 * Mongo id.
 */
@Document(collection = "projects")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String name = "";

    private @Nullable String title;

    /** Optional group the project belongs to ({@code ProjectGroupDocument.name}). */
    private @Nullable String projectGroupId;

    /** Teams that have access to this project ({@code TeamDocument.name}). */
    @Builder.Default
    private List<String> teamIds = new ArrayList<>();

    @Builder.Default
    private boolean enabled = true;

    /**
     * Classification of the project. {@link ProjectKind#NORMAL} for user projects,
     * {@link ProjectKind#SYSTEM} for hidden/protected projects such as the per-user
     * Vance Hub (see {@code specification/vance-engine.md} §2).
     */
    @Builder.Default
    private ProjectKind kind = ProjectKind.NORMAL;

    /** Lifecycle status — {@link ProjectStatus}. Pod-affinity is tracked separately via {@link #podIp}. */
    @Builder.Default
    private ProjectStatus status = ProjectStatus.INIT;

    /** IP of the pod currently owning the project, or {@code null} when unclaimed. */
    private @Nullable String podIp;

    /** When the current pod last refreshed its claim — used for stale-detection later. */
    private @Nullable Instant claimedAt;

    @CreatedDate
    private @Nullable Instant createdAt;
}
