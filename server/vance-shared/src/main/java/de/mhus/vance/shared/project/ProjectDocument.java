package de.mhus.vance.shared.project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        @CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }", unique = true),
        // Backs the per-beat lease renewal (one updateMulti over "everything I
        // hold") and every ownership query. Renewal cost stays independent of
        // the number of tenants and projects because of this index.
        @CompoundIndex(name = "home_pod_idx", def = "{ 'homePodId': 1 }"),
        // Backs the "who needs an owner but has no live lease" scan run by the
        // boot self-pull and the master distributor. An index range scan, where
        // the predecessor had to $nin a list that grew with the cluster.
        @CompoundIndex(name = "owner_required_idx",
                def = "{ 'ownerRequired': 1, 'lifecycleType': 1, 'status': 1, 'claimedAt': 1 }")
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

    /**
     * Lifecycle <em>intent</em> — {@link ProjectStatus}. {@code RUNNING}
     * means "should be live somewhere", not "a pod has it live": which pod
     * holds it right now is the lease below, and that expires on its own.
     * Keeping the two apart is what lets a crashed owner be recovered — see
     * {@code planning/project-ownership-lease.md} §2.
     */
    @Builder.Default
    private ProjectStatus status = ProjectStatus.INIT;

    /**
     * Owner lease — {@code BrainPodDocument.podId} of the pod holding the
     * project, or {@code null} when nobody does. Identity, not name: the
     * node name is operator-configurable ({@code vance.cluster.node-name}),
     * so a restarted pod would otherwise recognise its own dead
     * predecessor's claim as its own.
     *
     * <p>Together with {@link #claimedAt} this <em>is</em> the ownership
     * answer — never read either field directly, go through
     * {@code ProjectOwnership}.
     */
    private @Nullable String homePodId;

    /**
     * Cluster node name of the lease holder, denormalised for display and
     * logs ({@code BrainPodDocument.nodeName}). No decision depends on this
     * field; routing resolves the endpoint from {@link #homePodId}.
     */
    private @Nullable String homeNode;

    /**
     * When the lease holder last renewed the lease. Read by
     * {@code ProjectOwnership} to decide whether the claim still holds: a
     * value older than the configured lease TTL means the owner stopped
     * renewing, so the claim is expired and anyone may take over. An
     * expired lease is <em>inert</em>, not wrong — which is why nothing has
     * to be cleaned up when a pod dies.
     *
     * <p>The claim is its own heartbeat, so there is only one timestamp
     * here (unlike {@code MagratheaTaskDocument}, which needs to tell
     * "claimed long ago" from "still beating").
     */
    private @Nullable Instant claimedAt;

    /**
     * Drives cluster-wide spawn behaviour — see
     * {@code specification/cluster-project-management.md} §2. SYSTEM
     * projects are always {@link LifecycleType#HOMELESS}; NORMAL projects
     * default to {@link LifecycleType#AUTO}, where {@link #ownerRequired}
     * decides, and can be pinned either way by an operator.
     */
    @Builder.Default
    private LifecycleType lifecycleType = LifecycleType.AUTO;

    /**
     * Derived: does this project hold pod-local background work that has to
     * keep running when nobody is looking? True when it carries scheduler
     * entries or hooks — and only those. Event triggers are reactive
     * (something else already woke the project) and kit provisioning happens
     * once, so neither justifies pinning a project to a pod; the authority on
     * that list is {@code ProjectOwnerRequirementService
     * .ACTIVATION_SOURCE_PREFIXES}.
     *
     * <p>Maintained by {@code ProjectOwnerRequirementService} from document
     * change events, written only when the value flips. Never set by hand —
     * the operator knob is {@link #lifecycleType}.
     *
     * <p><b>Derived, not declared, on purpose.</b> Its predecessor
     * {@code requiresOwnerPod} was set by engine lifecycle <em>listeners</em>,
     * which made it circular: the flag only existed while the project was
     * loaded, so a project had to be running to be recognised as needing to
     * run. Deriving it from the presence of <em>documents</em> has no such
     * loop — a scheduler document exists whether or not its scheduler is
     * registered anywhere.
     */
    @Builder.Default
    private boolean ownerRequired = false;

    /**
     * Score the project contributes to a pod's {@code resourcesCurrentScore}
     * when claimed there. Default {@code 1}. Used by the placement fit stage to
     * decide which pod has room and by the Boot-Self-Pull cap.
     *
     * <p>Settable after create through {@code ProjectService.setPlacement}.
     * That is not the adaptive, measured score the spec defers to v2 — it is
     * the same act as setting the selector: something outside the brain
     * describing what this project needs. Without it, an external instance
     * could say <em>where</em> a project belongs but not <em>how much</em> it
     * costs, and half of the resource model would stay frozen at create time.
     */
    @Builder.Default
    private int homeResourceScore = 1;

    /**
     * What this project <b>requires</b> of a pod, as flat key/value pairs
     * matched against {@code BrainPodDocument.labels}. Read only through
     * {@code PodSelector}.
     *
     * <p>Empty — the default and the state of every project that predates the
     * field — matches every pod, so placement behaves exactly as it did before
     * labels existed. {@code null} on documents written earlier is the same
     * state, which is why this needs no migration.
     *
     * <p>Declarative: a change takes effect at the <em>next</em> placement and
     * never moves a running project. Moving one is a deliberate drain
     * ({@code planning/project-placement-labels.md} §8), because a move costs
     * engine teardown and a workspace rebuild and must not be a side effect of
     * a write that looks like an annotation.
     */
    @Builder.Default
    private @Nullable Map<String, String> placementSelector = new HashMap<>();

    @CreatedDate
    private @Nullable Instant createdAt;
}
