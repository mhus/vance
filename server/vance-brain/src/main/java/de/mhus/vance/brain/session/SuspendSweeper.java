package de.mhus.vance.brain.session;

import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.session.SuspendPolicy;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.shared.session.AbandonedSessionEvaluator;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically advances {@code SUSPENDED} sessions whose
 * {@code transitionAt} has passed to their next state: {@code ARCHIVED}
 * (default for user sessions, {@code onSuspend=KEEP}) or {@code CLOSED}
 * (daemon / event-driven, {@code onSuspend=CLOSE}). Sessions that pass
 * the {@link AbandonedSessionEvaluator} predicate are short-circuited
 * to {@code CLOSED} with {@link CloseReason#ABANDONED} so the archive
 * stays free of empty stubs.
 *
 * <p>See {@code specification/session-lifecycle.md} §9 + §9.1.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SuspendSweeper {

    private final SessionService sessionService;
    private final SessionLifecycleService lifecycleService;
    private final AbandonedSessionEvaluator abandonedSessionEvaluator;

    /**
     * Default sweep interval. 60 s strikes the balance between
     * snappiness on idle-driven moves and not hammering the DB.
     */
    @Scheduled(fixedDelayString = "${vance.session.suspend-sweeper.interval-ms:60000}",
            initialDelayString = "${vance.session.suspend-sweeper.initial-delay-ms:30000}")
    public void sweep() {
        Instant cutoff = Instant.now();
        List<SessionDocument> due = sessionService.findOverdueSuspended(cutoff);
        if (due.isEmpty()) return;
        log.info("Suspend-sweeper: {} session(s) due for transition", due.size());
        for (SessionDocument session : due) {
            // Re-check status — another pod may have raced us.
            if (session.getStatus() != SessionStatus.SUSPENDED) continue;
            try {
                if (abandonedSessionEvaluator.isAbandoned(session)) {
                    lifecycleService.closeWithCascade(
                            session.getSessionId(), CloseReason.ABANDONED);
                } else if (session.getOnSuspend() == SuspendPolicy.KEEP) {
                    lifecycleService.archiveWithCascade(session.getSessionId());
                } else {
                    lifecycleService.closeWithCascade(
                            session.getSessionId(), CloseReason.AUTO_CLOSE);
                }
            } catch (RuntimeException e) {
                log.error("Suspend-sweeper failed for session='{}': {}",
                        session.getSessionId(), e.toString(), e);
            }
        }
    }
}
