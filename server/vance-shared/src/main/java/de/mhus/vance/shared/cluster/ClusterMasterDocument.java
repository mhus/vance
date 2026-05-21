package de.mhus.vance.shared.cluster;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Single-row-per-cluster lease that names the current Cluster-Master pod.
 * One document per {@code clusterId} ({@code _id} = clusterId). See
 * {@code specification/cluster-project-management.md} §4.
 *
 * <p>{@code currentPodId} + {@code leaseUntil} form the CAS guard: an
 * empty/expired lease may be stolen by any pod, a live lease may only
 * be renewed by its current holder.
 */
@Document(collection = "cluster_master")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterMasterDocument {

    /** Cluster identifier — matches {@code ClusterProperties.id}. */
    @Id
    private String clusterId = "";

    /** {@code BrainPodDocument.podId} of the current master, or {@code null}. */
    private @Nullable String currentPodId;

    /** Human-readable node name of the master — informational, for logs/admin. */
    private @Nullable String currentNodeName;

    /** Endpoint ({@code host:port}) of the master — cached so callers don't need a join. */
    private @Nullable String currentEndpoint;

    /**
     * Wall-clock at which the lease expires. {@code null} means the slot
     * is unclaimed (initial state). A lease whose {@code leaseUntil} is
     * before {@code now} is considered stealable.
     */
    private @Nullable Instant leaseUntil;
}
