package de.mhus.vance.shared.cluster;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One row per running brain-pod incarnation. Refreshed on a heartbeat
 * tick — a missed beat past the configured stale window means
 * observers should treat the pod as gone (no janitor flips the
 * status; staleness is derived).
 *
 * <p>Identity:
 * <ul>
 *   <li>{@code podId} is unique cluster-wide and stable for the life of
 *       one Spring boot — typically a UUID generated on registration.</li>
 *   <li>{@code nodeName} is a human-friendly alias, unique within
 *       {@code clusterId}. Either explicitly configured via
 *       {@code vance.cluster.node-name} or assembled from a two-word
 *       dictionary at boot.</li>
 *   <li>{@code endpoint} is the {@code host:port} the pod advertises
 *       to peers — exactly the value {@code LocationService.getPodAddress()}
 *       returns and that callers reach via
 *       {@code ClusterService.resolveEndpoint(nodeName)}.</li>
 * </ul>
 *
 * <p>{@code activeProjects} is denormalised "{@code <tenantId>/<projectName>}"
 * entries, refreshed on every heartbeat. The authoritative source is
 * still {@code ProjectDocument.homeCluster}; this list is for the
 * cluster dashboard and quick "what runs on pod X" answers.
 */
@Document(collection = "brain_pods")
@CompoundIndexes({
        @CompoundIndex(
                name = "cluster_node_idx",
                def = "{ 'clusterId': 1, 'nodeName': 1 }",
                unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrainPodDocument {

    @Id
    private @Nullable String id;

    /** Cluster grouping (Spring property {@code vance.cluster.id}, default {@code "default"}). */
    @Indexed
    private String clusterId = "";

    /** Boot-stable, cluster-unique pod identifier (typically a UUID). */
    @Indexed(unique = true)
    private String podId = "";

    /** Human-friendly alias — unique within {@link #clusterId} via the compound index. */
    private String nodeName = "";

    /** Pod-self-advertised {@code host:port}; matches {@code LocationService.getPodAddress()}. */
    private String endpoint = "";

    /** Pod-self-reported lifecycle phase. {@code STALE} is observer-derived, not stored. */
    @Indexed
    private PodStatus status = PodStatus.STARTING;

    /** Set on registration; never updated. */
    private @Nullable Instant bootedAt;

    /** Refreshed on every heartbeat. Observers compute staleness from this field. */
    private @Nullable Instant lastHeartbeatAt;

    /**
     * Denormalised "{@code <tenantId>/<projectName>}" entries — the
     * projects this pod currently owns according to its own view.
     * Refreshed on every heartbeat.
     */
    @Builder.Default
    private List<String> activeProjects = new ArrayList<>();

    /** Optional build version — {@code vance.build.version}, kostet nichts. */
    private @Nullable String version;
}
