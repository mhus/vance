package de.mhus.vance.brain.trillian;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One cluster-wide lease of the Trillian loop (see
 * {@link TrillianWakeupClaimService}). The {@code _id} is
 * {@code lease/<key>}; the insert is the acquire, the owner-checked delete
 * the release.
 *
 * <p>A short TTL on {@code claimedAt} reaps leases whose holder died without
 * releasing — well past any takeover, which happens after the caller's own,
 * much shorter TTL.
 */
@Document(collection = "trillian_wakeup_claims")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrillianWakeupClaimDocument {

    @Id
    private String id;

    /** Acquire timestamp; TTL-reaped after an hour (well past any takeover). */
    @Indexed(expireAfterSeconds = 3600)
    private Instant claimedAt;

    /**
     * Holder identity ({@code TrillianWakeupClaimService#acquireLease}) — the
     * token the acquirer must present on release. Written on every takeover,
     * so a holder that outlives its TTL (GC pause, network partition) cannot
     * free its successor's lease.
     */
    private String holder;
}
