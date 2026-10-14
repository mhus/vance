package de.mhus.vance.shared.lease;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * One held resource lease. Written holder-first during acquisition
 * (PENDING with empty {@code levels}, then counter increments are pushed
 * into {@code levels}, then {@code HELD}) so a pod crash never leaves a
 * raised counter without a holder that knows about it — the reconcile
 * sweep recomputes counters from these documents.
 *
 * <p>{@code lastSeenAt} runs on Mongo server time ({@code $currentDate}
 * on every write, {@code $$NOW} in the reap filter) because holder and
 * reaper sit on different pods with different clocks. No Mongo TTL index:
 * deleting must also decrement the counters, which a TTL index cannot.
 */
@Document(collection = "resource_lease_holders")
@CompoundIndexes({
    // Reap by pod — all leases of a dead pod.
    @CompoundIndex(name = "pod_idx", def = "{ 'pod': 1 }"),
    // Listing / diagnostics per resource.
    @CompoundIndex(name = "resource_idx", def = "{ 'resource': 1 }")
})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ResourceLeaseHolderDocument {

    /** Holder token {@code rl-<uuid>} — also the identity of the {@link Lease}. */
    @Id
    private String token;

    /** PENDING while the funnel runs, HELD once every level is acquired. */
    private String state;

    /** Lease type — selects the {@link LeaseHandler} on revocation. */
    private String type;

    /** Resource key from the lease definition (counter identity). */
    private String resource;

    /** Consumer-specific instance key (process id, connection key, …). */
    private String holderRef;

    private String tenantId;

    @Nullable
    private String projectId;

    @Nullable
    private String userId;

    /** Pod id of the acquiring pod — the reaper's pod-liveness key. */
    private String pod;

    /** Whether the resource dies with the holder pod — decides revoke routing (§8). */
    private boolean podBound;

    /** Consumer-specific cleanup data for the handler. */
    @Nullable
    private Map<String, Object> payload;

    /**
     * Full counter keys this holder has booked ({@code <resource>|<level>|<scope>}).
     * Grows one entry per level as the funnel acquires; read by reconcile
     * to recompute the counters and by release to decrement exactly them.
     */
    @Builder.Default
    private List<String> levels = new ArrayList<>();

    /** Denormalized TTL of the lease definition at acquire time — the reap filter needs it. */
    private long ttlSeconds;

    private Instant acquiredAt;

    /** Server-time touch — every check() and write refreshes it; idle-reap cuts here. */
    @Indexed(name = "last_seen_idx")
    private Instant lastSeenAt;
}
