package de.mhus.vance.brain.eddie.connection;

import de.mhus.vance.api.thinkprocess.PlanProposedNotification;
import de.mhus.vance.api.thinkprocess.ProcessModeChangedNotification;
import de.mhus.vance.api.thinkprocess.TodosUpdatedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.eddie.plan.PlanFusionService;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan-frame handler — wires {@link PlanFusionService} into Eddie's
 * Working-WS receive path. Mirrors each {@code todos-updated} /
 * {@code plan-proposed} / {@code process-mode-changed} onto the
 * matching {@link WorkerLinkSnapshot}, persists, then pushes a
 * fused {@code todos-updated} to Eddie's user-session.
 *
 * <h2>What this handler does</h2>
 *
 * <ul>
 *   <li>{@code todos-updated} → updates {@code workerTodos} on the
 *       link, refreshes {@code lastSeen}, persists the snapshot,
 *       triggers fusion+push.</li>
 *   <li>{@code plan-proposed} → updates {@code planVersion}, persists,
 *       fusion+push.</li>
 *   <li>{@code process-mode-changed} → updates {@code workerMode},
 *       persists. Fusion+push fires too — the {@code <delegated_workers>}
 *       prompt block carries the mode, but the user-client also wants
 *       it (e.g. to render "PLANNING" badge on the worker's section).</li>
 *   <li>Anything else → silently ignored.</li>
 * </ul>
 *
 * <h2>Push target</h2>
 *
 * The fused notification goes to <i>Eddie's</i> own session — that's
 * where the user-client is connected. Workers' own {@code todos-updated}
 * frames continue to flow to their direct subscribers (if any user is
 * bound to the worker session); Eddie's fused view is additive.
 *
 * <p>See {@code planning/eddie-plan-mode.md} §2.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EddiePlanFrameHandler implements EddieFrameRouter.PlanFrameHandler {

    private final ThinkProcessService thinkProcessService;
    private final EddieWorkerConnectionPool pool;
    private final PlanFusionService fusionService;
    private final ClientEventPublisher clientEventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    public void onPlanFrame(WebSocketEnvelope envelope, WorkerLinkSnapshot link) {
        String type = envelope.getType();
        if (type == null) return;

        boolean changed = false;
        switch (type) {
            case MessageType.TODOS_UPDATED -> changed = applyTodosUpdated(envelope, link);
            case MessageType.PLAN_PROPOSED -> changed = applyPlanProposed(envelope, link);
            case MessageType.PROCESS_MODE_CHANGED -> changed = applyModeChanged(envelope, link);
            default -> { /* ignore */ }
        }
        if (!changed) return;

        link.setLastSeen(Instant.now());

        String eddieProcessId = pool.findEddieIdForWorker(link.getWorkerProcessId())
                .orElse(null);
        if (eddieProcessId == null) {
            log.debug("EddiePlanFrameHandler: no Eddie owner for worker={}, skipping persist+push",
                    link.getWorkerProcessId());
            return;
        }

        try {
            thinkProcessService.upsertWorkerLink(eddieProcessId, link);
        } catch (RuntimeException e) {
            log.warn("EddiePlanFrameHandler: upsertWorkerLink failed eddie={} worker={}: {}",
                    eddieProcessId, link.getWorkerProcessId(), e.toString());
            return;
        }

        // Re-read the Eddie document so the fused notification reflects
        // the freshly-persisted snapshot plus any concurrent changes
        // from the chat-frame handler.
        thinkProcessService.findById(eddieProcessId).ifPresent(this::publishFused);
    }

    private boolean applyTodosUpdated(WebSocketEnvelope envelope, WorkerLinkSnapshot link) {
        try {
            TodosUpdatedNotification msg = objectMapper.convertValue(
                    envelope.getData(), TodosUpdatedNotification.class);
            if (msg == null) return false;
            link.setWorkerTodos(msg.getTodos() == null
                    ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(msg.getTodos()));
            return true;
        } catch (RuntimeException e) {
            log.debug("malformed todos-updated for worker={}: {}",
                    link.getWorkerProcessId(), e.toString());
            return false;
        }
    }

    private boolean applyPlanProposed(WebSocketEnvelope envelope, WorkerLinkSnapshot link) {
        try {
            PlanProposedNotification msg = objectMapper.convertValue(
                    envelope.getData(), PlanProposedNotification.class);
            if (msg == null) return false;
            link.setPlanVersion(msg.getPlanVersion());
            return true;
        } catch (RuntimeException e) {
            log.debug("malformed plan-proposed for worker={}: {}",
                    link.getWorkerProcessId(), e.toString());
            return false;
        }
    }

    private boolean applyModeChanged(WebSocketEnvelope envelope, WorkerLinkSnapshot link) {
        try {
            ProcessModeChangedNotification msg = objectMapper.convertValue(
                    envelope.getData(), ProcessModeChangedNotification.class);
            if (msg == null) return false;
            link.setWorkerMode(msg.getNewMode());
            return true;
        } catch (RuntimeException e) {
            log.debug("malformed process-mode-changed for worker={}: {}",
                    link.getWorkerProcessId(), e.toString());
            return false;
        }
    }

    private void publishFused(ThinkProcessDocument eddie) {
        if (eddie.getSessionId() == null || eddie.getSessionId().isBlank()) return;
        TodosUpdatedNotification fused = fusionService.fuse(eddie);
        try {
            clientEventPublisher.publish(eddie.getSessionId(), MessageType.TODOS_UPDATED, fused);
        } catch (RuntimeException e) {
            log.debug("publish fused todos-updated failed for session='{}': {}",
                    eddie.getSessionId(), e.toString());
        }
    }
}
