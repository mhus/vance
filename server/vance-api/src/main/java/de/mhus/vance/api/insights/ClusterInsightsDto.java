package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Cluster dashboard payload — the {@link #pods pods list} every brain
 * in this brain's cluster plus the metadata of the current Cluster-
 * Master lease. {@code masterPodId}/{@code masterNodeName} stay
 * {@code null} when no pod currently holds the lease.
 *
 * <p>Per-pod {@code master} mirrors {@link #masterPodId} for the row
 * convenience of the UI — callers can render the master badge without
 * joining the wrapper field.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class ClusterInsightsDto {

    /** Cluster grouping ({@code vance.cluster.id}). */
    private String clusterId;

    /** {@code BrainPodDocument.podId} of the current master, or {@code null}. */
    private @Nullable String masterPodId;

    /** Human-readable node name of the master, or {@code null}. */
    private @Nullable String masterNodeName;

    /** Endpoint ({@code host:port}) of the master, or {@code null}. */
    private @Nullable String masterEndpoint;

    /** Wall-clock at which the master lease expires, or {@code null} if unclaimed. */
    private @Nullable Instant masterLeaseUntil;

    /** One row per brain-pod registered in this cluster. */
    @Builder.Default
    private List<BrainPodInsightsDto> pods = new ArrayList<>();
}
