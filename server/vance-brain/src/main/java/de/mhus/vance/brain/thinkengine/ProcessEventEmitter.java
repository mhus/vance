package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The single point that knows how to deliver an inbox message to a
 * think-process's lane: append to the persistent queue, then schedule
 * a drain that calls {@link ThinkEngineService#steer} once per
 * decoded message.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@link ParentNotificationListener} — child status transitions
 *       routed back to the parent.</li>
 *   <li>{@code DefaultProcessOrchestrator} — engine-driven
 *       {@code notifyParent} calls.</li>
 *   <li>{@code ProcessSteerHandler} — directly via
 *       {@link #drainAndDeliver(String)} from inside its own lane
 *       task, so the same drain-loop semantics apply on the
 *       client-driven path.</li>
 * </ul>
 *
 * <p><b>Drain-loop / Auto-Wakeup.</b> {@link #drainAndDeliver}
 * keeps re-draining the queue until it's empty. Messages that arrive
 * while {@code engine.steer} is running fall into the freshly-emptied
 * queue and are picked up by the next loop iteration in the same
 * lane-turn — the formal "schedule another turn" pattern from the
 * spec collapses to "stay in this turn until empty," which holds the
 * lane briefly longer but avoids re-submission overhead.
 *
 * <p>{@link ObjectProvider} for {@link ThinkEngineService} because
 * the latter wires through {@code ToolDispatcher → ServerToolSource
 * → process tools → ThinkEngineService}; a direct dep would close
 * the cycle.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessEventEmitter {

    private final ThinkProcessService thinkProcessService;
    private final LaneScheduler laneScheduler;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;

    /**
     * Appends a {@code PROCESS_EVENT} to {@code parentProcessId}'s
     * pending queue and schedules a wakeup on the parent's lane.
     *
     * @return {@code true} when the parent existed and the message
     *         was queued, {@code false} otherwise
     */
    public boolean notifyParent(
            String parentProcessId,
            String sourceProcessId,
            ProcessEventType type,
            @Nullable String humanSummary,
            @Nullable Map<String, Object> payload) {

        PendingMessageDocument doc = PendingMessageDocument.builder()
                .type(PendingMessageType.PROCESS_EVENT)
                .at(Instant.now())
                .sourceProcessId(sourceProcessId)
                .eventType(type)
                .content(humanSummary)
                .payload(payload)
                .build();

        if (!thinkProcessService.appendPending(parentProcessId, doc)) {
            log.warn("Parent notify dropped — parent process not found id='{}' (child='{}', event={})",
                    parentProcessId, sourceProcessId, type);
            return false;
        }
        scheduleDrain(parentProcessId);
        return true;
    }

    /**
     * Submits a drain-and-deliver task on the process's own lane —
     * the thread that picks it up will see a serialized view of any
     * other lane work for that process.
     */
    public void scheduleDrain(String processId) {
        laneScheduler.submit(processId, () -> {
            drainAndDeliver(processId);
            return null;
        });
    }

    /**
     * Drains the process's pending queue and dispatches each message
     * to {@link ThinkEngineService#steer}, looping until the queue
     * stays empty across a full pass. Must run on the process's lane
     * — the caller is responsible for that, either by calling this
     * inside a {@code laneScheduler.submit} block or via
     * {@link #scheduleDrain(String)}.
     */
    public void drainAndDeliver(String processId) {
        Optional<ThinkProcessDocument> processOpt = thinkProcessService.findById(processId);
        if (processOpt.isEmpty()) {
            log.warn("drainAndDeliver: process gone id='{}'", processId);
            return;
        }
        ThinkProcessDocument process = processOpt.get();
        ThinkEngineService engine = thinkEngineServiceProvider.getObject();

        while (true) {
            List<PendingMessageDocument> drained = thinkProcessService.drainPending(processId);
            if (drained.isEmpty()) {
                return;
            }
            for (PendingMessageDocument d : drained) {
                try {
                    engine.steer(process, SteerMessageCodec.toMessage(d));
                } catch (RuntimeException re) {
                    log.warn("Drain steer failed id='{}' type={} : {}",
                            processId, d.getType(), re.toString());
                }
            }
            // Refresh after a full pass — engine work may have updated status,
            // and the next steer should see the latest snapshot.
            process = thinkProcessService.findById(processId).orElse(process);
        }
    }
}
