package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Routes life-cycle events from a child think-process back to its
 * parent (orchestrator) — the server-side half of the
 * Coordinator/Worker-Pattern (see {@code arthur-engine.md} §3.5).
 *
 * <p>Two things happen on a status transition that the parent cares
 * about:
 * <ol>
 *   <li>A {@link PendingMessageType#PROCESS_EVENT} is appended to the
 *       parent's persistent inbox so it survives crashes.</li>
 *   <li>A wakeup task is queued on the parent's lane: it drains the
 *       inbox and feeds each queued message to the parent engine via
 *       {@link ThinkEngineService#steer(ThinkProcessDocument, SteerMessage)}.</li>
 * </ol>
 *
 * <p>The wakeup uses {@link ObjectProvider} for {@link ThinkEngineService}
 * because the latter wires through {@code ToolDispatcher → ServerToolSource
 * → process tools → ThinkEngineService}, and adding a direct dep here would
 * close the cycle.
 *
 * <p>Filtering: only transitions that are <em>materially different</em>
 * for the parent get propagated. Suppressing the SAME-status update or
 * intermediate {@code RUNNING} flips keeps the parent's inbox clean and
 * its lane idle.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ParentNotificationListener {

    private final ThinkProcessService thinkProcessService;
    private final LaneScheduler laneScheduler;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        String parentId = event.parentProcessId();
        if (parentId == null) {
            return; // top-level process — nobody to notify
        }
        if (event.priorStatus() == event.newStatus()) {
            return; // pure heartbeat — no transition
        }
        ProcessEventType eventType = mapStatus(event.newStatus());
        if (eventType == null) {
            return; // intermediate (READY/RUNNING/PAUSED/SUSPENDED) — no parent ping
        }

        PendingMessageDocument doc = PendingMessageDocument.builder()
                .type(PendingMessageType.PROCESS_EVENT)
                .at(Instant.now())
                .sourceProcessId(event.processId())
                .eventType(eventType)
                .content(humanSummary(event.processId(), event.newStatus()))
                .build();

        boolean appended = thinkProcessService.appendPending(parentId, doc);
        if (!appended) {
            log.warn("Parent notify dropped — parent process not found id='{}' (child='{}', event={})",
                    parentId, event.processId(), eventType);
            return;
        }
        log.info("Parent notify queued parent='{}' child='{}' event={}",
                parentId, event.processId(), eventType);

        laneScheduler.submit(parentId, () -> {
            wakeup(parentId);
            return null;
        });
    }

    /** Drains the parent's inbox on its lane and delivers each message to the engine. */
    private void wakeup(String parentId) {
        Optional<ThinkProcessDocument> parent = thinkProcessService.findById(parentId);
        if (parent.isEmpty()) {
            log.warn("Wakeup skipped — parent gone id='{}'", parentId);
            return;
        }
        List<PendingMessageDocument> drained = thinkProcessService.drainPending(parentId);
        if (drained.isEmpty()) {
            // Another wakeup beat us to the drain — fine, nothing to do.
            return;
        }
        ThinkEngineService engineService = thinkEngineServiceProvider.getObject();
        for (PendingMessageDocument d : drained) {
            try {
                engineService.steer(parent.get(), SteerMessageCodec.toMessage(d));
            } catch (RuntimeException re) {
                log.warn("Parent wakeup steer failed parent='{}' type={} : {}",
                        parentId, d.getType(), re.toString());
            }
        }
    }

    private static @Nullable ProcessEventType mapStatus(ThinkProcessStatus status) {
        return switch (status) {
            case DONE -> ProcessEventType.DONE;
            case BLOCKED -> ProcessEventType.BLOCKED;
            case STOPPED -> ProcessEventType.STOPPED;
            case STALE -> ProcessEventType.FAILED;
            case READY, RUNNING, PAUSED, SUSPENDED -> null;
        };
    }

    private static String humanSummary(String childProcessId, ThinkProcessStatus status) {
        return "Child process " + childProcessId + " status=" + status.name().toLowerCase();
    }
}
