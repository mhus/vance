package de.mhus.vance.brain.eddie;

import de.mhus.vance.api.eddie.MediateHandoverNotification;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.eddie.connection.EddieFrameRouter;
import de.mhus.vance.brain.eddie.connection.EddieWorkerConnectionPool;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * When a worker process Eddie is currently mediating reaches a
 * terminal status (DONE / CLOSED), the user-WS would be stuck on a
 * dead session. This listener kicks the client back to Eddie's
 * session automatically — same cleanup as the {@code MediationEndHandler}
 * runs on a {@code /hub} click.
 *
 * <p>See {@code specification/eddie-engine.md} §8.5.5 — third
 * trigger ("worker terminal").
 *
 * <p>Single-pod scope for now: when Eddie and the terminating worker
 * live on different brain pods, the status event fires on the worker
 * pod and the lookup runs there too. The Mongo query is global, so
 * the right Eddie record is found; the rebind frame is pushed via
 * {@link ClientEventPublisher}, which only delivers when the user-WS
 * is bound on this pod. Cross-pod auto-rebind is a follow-up
 * (Eddie-Pod-side trigger via {@code EngineMessageRouter}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediationAutoRebindListener {

    private static final Duration JWT_TTL = Duration.ofMinutes(15);

    private final ThinkProcessService thinkProcessService;
    private final EddieWorkerConnectionPool workerConnectionPool;
    private final EddieFrameRouter workerFrameRouter;
    private final ClientEventPublisher clientEventPublisher;
    private final JwtService jwtService;
    private final MongoTemplate mongoTemplate;

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        if (event.newStatus() != ThinkProcessStatus.CLOSED) return;
        // CLOSED is the only terminal worker status that should
        // auto-bounce the user back. BLOCKED stays put — Eddie may
        // want the user to answer the worker directly.

        Optional<ThinkProcessDocument> eddieOpt = findEddieMediating(event.processId());
        if (eddieOpt.isEmpty()) return;

        ThinkProcessDocument eddie = eddieOpt.get();
        if (eddie.getMediation() == null) return;

        log.info("Mediation auto-rebind: worker='{}' terminal, bouncing eddie='{}' user back",
                event.processId(), eddie.getId());

        // Re-open the Working-WS so Eddie's frame handlers see the
        // worker's final state on her next turn (best-effort — worker
        // is closing, frames may not arrive).
        Optional<WorkerLinkSnapshot> link = thinkProcessService.findWorkerLink(
                eddie.getId(), event.processId());
        if (link.isPresent()) {
            try {
                String jwt = jwtService.createToken(
                        eddie.getTenantId(),
                        userIdOf(eddie),
                        Instant.now().plus(JWT_TTL));
                workerConnectionPool.openOrReuse(
                        eddie.getId(), link.get(), jwt, workerFrameRouter);
            } catch (RuntimeException e) {
                log.debug("auto-rebind: pool reopen failed (worker terminal anyway): {}",
                        e.toString());
            }
        }

        // Clear mediation flag so Eddie's lane resumes on next turn.
        thinkProcessService.clearMediation(eddie.getId());

        // Push a reverse handover at Eddie's session — but it'll only
        // arrive if the user-WS is currently on the worker session AND
        // bound on this pod. Worst case: client never sees it; user
        // notices the worker died and presses /hub themselves.
        MediateHandoverNotification reverse = MediateHandoverNotification.builder()
                .eddieProcessId(eddie.getId() == null ? "" : eddie.getId())
                .eddieSessionId(eddie.getSessionId())
                .targetSessionId(eddie.getSessionId())
                .targetProcessName(eddie.getName())
                .voiceAnnouncement("Der Worker ist fertig. Ich bin wieder da.")
                .build();
        try {
            // Push to the worker session — that's where the user
            // currently is. If they're on Eddie's session already
            // (race), the publish is a no-op and that's fine.
            clientEventPublisher.publish(
                    eddie.getMediation().getWorkerSessionId(),
                    MessageType.MEDIATE_HANDOVER, reverse);
        } catch (RuntimeException e) {
            log.debug("auto-rebind: handover push failed: {}", e.toString());
        }
    }

    /**
     * Finds the (single) Eddie process whose
     * {@code mediation.workerProcessId} matches the terminating worker.
     * Pure Mongo query — no compound index needed at this volume.
     */
    private Optional<ThinkProcessDocument> findEddieMediating(String workerProcessId) {
        Query q = new Query(Criteria.where("mediation.workerProcessId").is(workerProcessId));
        return Optional.ofNullable(mongoTemplate.findOne(q, ThinkProcessDocument.class));
    }

    private String userIdOf(ThinkProcessDocument eddie) {
        String project = eddie.getProjectId();
        if (project != null && project.startsWith("_user_")) {
            return project.substring("_user_".length());
        }
        return eddie.getTenantId();
    }
}
