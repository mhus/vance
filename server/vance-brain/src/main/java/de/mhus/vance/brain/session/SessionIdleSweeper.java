package de.mhus.vance.brain.session;

import de.mhus.vance.api.session.IdlePolicy;
import de.mhus.vance.api.session.SuspendCause;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically moves {@code RUNNING}/{@code IDLE} sessions with
 * {@link IdlePolicy#SUSPEND} into {@code SUSPENDED} once they've been
 * quiet for at least their own {@code idleTimeoutMs}. From there the
 * {@link SuspendSweeper} carries them on to {@code ARCHIVED}/{@code CLOSED}
 * per {@code onSuspend}.
 *
 * <p>Spec: {@code specification/session-lifecycle.md} §7. A session
 * counts as idle when no engine is in {@code RUNNING}, {@code BLOCKED}
 * or {@code PAUSED} — otherwise the user is "dran" or work is in flight.
 *
 * <p>The sweep itself is cheap: a coarse Mongo pre-filter on
 * {@code status_activity_idx} returns only candidates older than
 * {@code coarseCutoffSeconds}; in-app we then compare each session's own
 * {@code idleTimeoutMs} (per-session, immutable from the recipe), and
 * only for the survivors load the process list.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionIdleSweeper {

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final SessionLifecycleService lifecycleService;

    /**
     * Coarse pre-filter floor — sessions touched in the last N seconds
     * never get the per-session timeout check. Picked well below the
     * smallest practical {@code idleTimeoutMs} so it never racing-trips
     * a freshly-touched session into the in-app check unnecessarily.
     */
    @Value("${vance.session.idle-sweeper.coarse-cutoff-seconds:30}")
    private long coarseCutoffSeconds;

    @Scheduled(fixedDelayString = "${vance.session.idle-sweeper.interval-ms:60000}",
            initialDelayString = "${vance.session.idle-sweeper.initial-delay-ms:45000}")
    public void sweep() {
        Instant now = Instant.now();
        Instant coarseCutoff = now.minusSeconds(coarseCutoffSeconds);
        List<SessionDocument> candidates =
                sessionService.findIdleCandidates(coarseCutoff);
        if (candidates.isEmpty()) return;

        int suspended = 0;
        for (SessionDocument session : candidates) {
            if (!isIdleLongEnough(session, now)) continue;
            if (hasActiveEngine(session)) continue;
            try {
                lifecycleService.suspendCascade(
                        session.getSessionId(), SuspendCause.IDLE);
                suspended++;
            } catch (RuntimeException e) {
                log.error("Idle-sweep failed for session='{}': {}",
                        session.getSessionId(), e.toString(), e);
            }
        }
        if (suspended > 0) {
            log.info("Idle-sweep suspended {} session(s) (of {} candidate(s))",
                    suspended, candidates.size());
        }
    }

    private boolean isIdleLongEnough(SessionDocument session, Instant now) {
        Instant last = session.getLastActivityAt();
        if (last == null) return false;
        long ageMs = now.toEpochMilli() - last.toEpochMilli();
        return ageMs >= session.getIdleTimeoutMs();
    }

    /**
     * A session is "active" if any of its processes is in a status that
     * either runs the engine or waits for the user. {@code IDLE} and
     * {@code SUSPENDED} processes (and {@code CLOSED}/{@code INIT}) don't
     * count as activity for idle-suspend purposes. See spec §7 table.
     */
    private boolean hasActiveEngine(SessionDocument session) {
        List<ThinkProcessDocument> processes = thinkProcessService.findBySession(
                session.getTenantId(), session.getSessionId());
        for (ThinkProcessDocument p : processes) {
            ThinkProcessStatus s = p.getStatus();
            if (s == ThinkProcessStatus.RUNNING
                    || s == ThinkProcessStatus.BLOCKED
                    || s == ThinkProcessStatus.PAUSED) {
                return true;
            }
        }
        return false;
    }
}
