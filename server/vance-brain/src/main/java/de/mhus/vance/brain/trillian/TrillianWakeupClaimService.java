package de.mhus.vance.brain.trillian;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Atomic cross-pod claim for a Trillian self-check slot. The heartbeat tick
 * runs on every pod (D2: the loop homes are podless hubs with no owner pod),
 * so firing needs an arbiter: {@link #claim} inserts one
 * {@link TrillianWakeupClaimDocument} keyed by
 * {@code (tenant, loopProcessId, dueEpochMillis)} — the unique {@code _id}
 * makes the insert that arbiter. First pod wins and fires, the others skip.
 *
 * <p>Only the wake is gated. Reading findings and re-arming are cheap and
 * idempotent enough to race; the thing that must not happen twice is the
 * turn.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrillianWakeupClaimService {

    private final MongoTemplate mongoTemplate;

    /**
     * Try to claim the wake slot of {@code due} for this loop. Returns
     * {@code true} when this pod won the slot and should wake the loop.
     */
    public boolean claim(String tenantId, String loopProcessId, Instant due) {
        String id = tenantId + '/' + loopProcessId + '/' + due.toEpochMilli();
        try {
            mongoTemplate.insert(TrillianWakeupClaimDocument.builder()
                    .id(id)
                    .claimedAt(Instant.now())
                    .build());
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("Trillian wakeup slot '{}' already claimed by another pod — skipping", id);
            return false;
        }
    }
}
