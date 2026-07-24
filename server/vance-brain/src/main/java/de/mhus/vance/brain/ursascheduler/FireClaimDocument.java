package de.mhus.vance.brain.ursascheduler;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Cross-pod fire claim for one scheduler tick-slot. The {@code _id} is
 * {@code tenant/project/scheduler/epochSecond}, so an atomic insert lets
 * exactly one pod win a given tick — the loser hits a duplicate-key and
 * skips (see {@link UrsaFireClaimService}). Closes the handover-window
 * double-fire between two pods that briefly both hold the scheduler.
 *
 * <p>A short TTL on {@code claimedAt} reaps old slots — the claim only needs
 * to outlive the handover window, not the run.
 */
@Document(collection = "ursa_fire_claims")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FireClaimDocument {

    @Id
    private String id;

    /** Claim timestamp; TTL-reaped after an hour (well past any handover). */
    @Indexed(expireAfterSeconds = 3600)
    private Instant claimedAt;
}
