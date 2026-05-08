package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.eddie.MediateHandoverNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.eddie.connection.EddieFrameRouter;
import de.mhus.vance.brain.eddie.connection.EddieWorkerConnectionPool;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.eddie.Mediation;
import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Server-side handling of the client's {@code mediation-end} frame —
 * see {@code specification/eddie-engine.md} §8.5.5 + §8.5.4.
 *
 * <p>The frame is sent by foot ({@code /hub} slash command) or web
 * (the back-to-hub UI button) when the user wants to come back to
 * Eddie from a worker session that Eddie handed them over to. The
 * server intercepts the frame (it is <b>not</b> forwarded to the
 * worker), runs the cleanup sequence, and bounces the client back to
 * Eddie's session through a {@code mediate-handover} pointing at the
 * Eddie session id.
 *
 * <h2>Cleanup sequence</h2>
 *
 * <ol>
 *   <li>Look up Eddie's process from the active mediation record.</li>
 *   <li>Re-open the Working-WS pool entry for the worker so Eddie's
 *       chat- / plan-frame handlers resume mirroring on her next
 *       turn.</li>
 *   <li>Clear the {@code mediation} field on Eddie's process — this
 *       is what re-activates the LLM lane on the next runTurn.</li>
 *   <li>Send a {@code mediate-handover} frame pointing at Eddie's
 *       session, so the client's {@code session-resume} flow runs
 *       symmetrically with the original handover.</li>
 * </ol>
 *
 * <p>If the frame arrives outside an active mediation (client bug,
 * race), we log and silently ack — no point bouncing the client
 * around.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediationEndHandler implements WsHandler {

    private static final Duration JWT_TTL = Duration.ofMinutes(15);

    private final WebSocketSender sender;
    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final EddieWorkerConnectionPool workerConnectionPool;
    private final EddieFrameRouter workerFrameRouter;
    private final JwtService jwtService;
    private final SessionConnectionRegistry connectionRegistry;
    private final MongoTemplate mongoTemplate;

    @Override
    public String type() {
        return MessageType.MEDIATION_END;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return ctx.hasSession();
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        String boundSessionId = ctx.getSessionId();
        if (boundSessionId == null) {
            sender.sendReply(wsSession, envelope, MessageType.MEDIATION_END, null);
            return;
        }

        // Find Eddie's process by walking the mediation records — the
        // user-bound session id is the worker session referenced in
        // exactly one active mediation.
        Optional<ThinkProcessDocument> eddieOpt = findEddieByActiveWorkerSession(boundSessionId);
        if (eddieOpt.isEmpty()) {
            log.debug("mediation-end on session='{}' — no active mediation, no-op",
                    boundSessionId);
            sender.sendReply(wsSession, envelope, MessageType.MEDIATION_END, null);
            return;
        }
        ThinkProcessDocument eddie = eddieOpt.get();
        Mediation mediation = eddie.getMediation();
        if (mediation == null) {
            sender.sendReply(wsSession, envelope, MessageType.MEDIATION_END, null);
            return;
        }

        // Re-open the Working-WS so Eddie's frame handlers see live
        // worker output again on her next turn. Best-effort.
        Optional<WorkerLinkSnapshot> linkOpt = thinkProcessService.findWorkerLink(
                eddie.getId(), mediation.getWorkerProcessId());
        if (linkOpt.isPresent()) {
            try {
                String jwt = jwtService.createToken(
                        eddie.getTenantId(),
                        userIdOf(eddie),
                        Instant.now().plus(JWT_TTL));
                workerConnectionPool.openOrReuse(
                        eddie.getId(), linkOpt.get(), jwt, workerFrameRouter);
            } catch (RuntimeException e) {
                log.debug("mediation-end: pool reopen failed for worker={}: {}",
                        mediation.getWorkerProcessId(), e.toString());
            }
        }

        // Clear the mediation flag — this re-enables the LLM lane.
        thinkProcessService.clearMediation(eddie.getId());
        log.info("mediation-end: eddie='{}' worker='{}' — bouncing client back",
                eddie.getId(), mediation.getWorkerProcessId());

        // Tell the client to rebind to Eddie's session. Symmetric to
        // the original handover — the client's session-resume path
        // does the actual bind work.
        MediateHandoverNotification reverse = MediateHandoverNotification.builder()
                .eddieProcessId(eddie.getId() == null ? "" : eddie.getId())
                .eddieSessionId(eddie.getSessionId())
                .targetSessionId(eddie.getSessionId())
                .targetProcessName(eddie.getName())
                .build();
        sender.sendNotification(wsSession, MessageType.MEDIATE_HANDOVER, reverse);
        sender.sendReply(wsSession, envelope, MessageType.MEDIATION_END, null);
    }

    /**
     * Mongo lookup: find the (single) Eddie think-process whose
     * {@code mediation.workerSessionId} equals {@code workerSessionId}.
     * Field-on-embedded-doc query — no compound index needed for v1
     * (mediations are at most one per Eddie process).
     */
    private Optional<ThinkProcessDocument> findEddieByActiveWorkerSession(String workerSessionId) {
        Query query = new Query(Criteria.where("mediation.workerSessionId").is(workerSessionId));
        return Optional.ofNullable(mongoTemplate.findOne(query, ThinkProcessDocument.class));
    }

    /**
     * The Eddie process lives in {@code _user_<userId>} — derive the
     * username from there. Defensive: fall back to the session record
     * if the project-name convention isn't intact.
     */
    private String userIdOf(ThinkProcessDocument eddie) {
        String project = eddie.getProjectId();
        if (project != null && project.startsWith("_user_")) {
            return project.substring("_user_".length());
        }
        return sessionService.findBySessionId(eddie.getSessionId())
                .map(SessionDocument::getUserId)
                .orElse(eddie.getTenantId());
    }
}
