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

    /** Lifecycle status — {@link ProjectStatus}. Pod-affinity is tracked separately via {@link #homeCluster}. */
    @Builder.Default
    private ProjectStatus status = ProjectStatus.INIT;

    /**
     * Cluster node name of the pod currently owning the project, or
     * {@code null} when unclaimed. References
     * {@code BrainPodDocument.nodeName} — IP+port is looked up on demand
     * via {@code ClusterService.resolveEndpoint(homeCluster)} so that pod
     * restarts (which mint a fresh node name) never inherit stale claims.
     */
    private @Nullable String homeCluster;

    /** When the current pod last refreshed its claim — used for stale-detection later. */
    private @Nullable Instant claimedAt;

    /**
     * {@code true} when this project carries owner-pod-bound engine state
     * (today: an active {@code SchedulerService}; future engines may also
     * flip this). Maintained by engine-lifecycle listeners and read by
     * {@code ProjectWakeupTick} to decide which RUNNING-but-unclaimed
     * projects need an active owner-pod again after a cluster-node death.
     */
    @Builder.Default
    private boolean requiresOwnerPod = false;

    @CreatedDate
    private @Nullable Instant createdAt;
}
