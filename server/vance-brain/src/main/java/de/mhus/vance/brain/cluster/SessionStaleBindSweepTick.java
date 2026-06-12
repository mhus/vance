package de.mhus.vance.brain.cluster;

import de.mhus.vance.shared.session.SessionService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide periodic sweep that releases {@code boundConnectionId}
 * on sessions whose last heartbeat is older than
 * {@link SessionService#bindStaleAfter()}.
 *
 * <p>Runs on every pod but no-ops unless the local pod currently holds
 * the Cluster-Master lease — same pattern as {@link ClusterCleanupTick}.
 * One master means one sweep per round, no Mongo write contention from
 * multiple pods racing on the same documents.
 *
 * <p>The sweep is index-supported ({@code bound_activity_idx}, partial
 * filter on {@code boundConnectionId}) and runs as a server-side
 * {@code updateMulti} — no documents are loaded into the JVM, so the
 * cost is independent of the total session count.
 *
 * <p>Without this sweep, sessions for projects that no current pod owns
 * (e.g. {@code _user_*}, {@code _tenant}, archived projects) keep their
 * stale {@code boundConnectionId} forever — the project-bring path
 * cleans those only when a project is actively brought online.
 */
@Component
@ConditionalOnProperty(name = "vance.cluster.master.enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SessionStaleBindSweepTick {

    private final ClusterMasterService masterService;
    private final SessionService sessionService;

    @Scheduled(fixedDelayString = "${vance.session.staleBindSweep.interval:PT60S}",
            initialDelayString = "${vance.session.staleBindSweep.initialDelay:PT2M}")
    public void tick() {
        if (!masterService.isLocalPodMaster()) {
            return;
        }
        try {
            sweep(Instant.now());
        } catch (RuntimeException e) {
            log.warn("SessionStaleBindSweepTick: sweep failed: {}", e.toString());
        }
    }

    /**
     * Pure sweep — extracted so tests can drive it deterministically.
     * Returns the number of sessions that were unbound.
     */
    long sweep(Instant now) {
        Instant cutoff = now.minus(sessionService.bindStaleAfter());
        return sessionService.unbindStaleConnections(cutoff);
    }
}
