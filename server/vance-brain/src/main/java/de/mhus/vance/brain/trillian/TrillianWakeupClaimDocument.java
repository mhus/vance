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
 * Cross-pod wake claim for one self-check slot of a Trillian user-loop. The
 * {@code _id} is {@code tenant/loopId/dueEpochMillis}, so an atomic insert
 * lets exactly one pod wake the loop for a given appointment — the loser
 * hits a duplicate-key and skips (see {@link TrillianWakeupClaimService}).
 *
 * <p>Mirror of {@code ursascheduler.FireClaimDocument}: same arbiter trick,
 * different subsystem. The claim replaces the old "only the home pod scans"
 * rule, which a podless hub cannot provide (D2).
 *
 * <p>A short TTL on {@code claimedAt} reaps old slots — so a pod that dies
 * between claiming and waking loses one round, not the appointment: the
 * slot is free again within the hour and the loop is still due.
 */
@Document(collection = "trillian_wakeup_claims")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrillianWakeupClaimDocument {

    @Id
    private String id;

    /** Claim timestamp; TTL-reaped after an hour (well past any handover). */
    @Indexed(expireAfterSeconds = 3600)
    private Instant claimedAt;
}
