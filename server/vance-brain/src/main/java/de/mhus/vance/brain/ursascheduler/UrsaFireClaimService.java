package de.mhus.vance.brain.ursascheduler;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Atomic cross-pod claim for a scheduler's cron tick-slot. Firing otherwise
 * rides a pod-local in-memory {@code TaskScheduler}; during a project handover
 * (migration / split-brain) two pods can briefly both hold the scheduler and
 * both fire the same tick, double-spawning the work.
 *
 * <p>{@link #claim} inserts one {@link FireClaimDocument} keyed by
 * {@code (tenant, project, scheduler, epochSecond)}. The unique {@code _id}
 * makes the insert the atomic arbiter: the first pod wins and fires, the
 * loser catches {@link DuplicateKeyException} and skips. Only the cron path is
 * gated — manual {@code fireNow} bypasses this and always fires.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UrsaFireClaimService {

    private final MongoTemplate mongoTemplate;

    /**
     * Try to claim {@code (tenant, project, scheduler)} for the tick-slot at
     * {@code slot} (quantized to the second — the cron resolution). Returns
     * {@code true} when this pod won the slot and should fire, {@code false}
     * when another pod already claimed it.
     */
    public boolean claim(String tenantId, String projectId, String scheduler, Instant slot) {
        String id = tenantId + '/' + projectId + '/' + scheduler + '/' + slot.getEpochSecond();
        try {
            mongoTemplate.insert(FireClaimDocument.builder()
                    .id(id)
                    .claimedAt(Instant.now())
                    .build());
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("Fire slot '{}' already claimed by another pod — skipping", id);
            return false;
        }
    }
}
