package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The single point that knows how to deliver inbox traffic to a
 * think-process's lane: append a {@link PendingMessageDocument} to
 * the persistent queue, then schedule a lane-turn that lets the
 * engine drain the queue itself via
 * {@link ThinkEngineContext#drainPending()}.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@link ParentNotificationListener} — child status transitions
 *       routed back to the parent.</li>
 *   <li>{@code DefaultProcessOrchestrator} — engine-driven
 *       {@code notifyParent} calls.</li>
 *   <li>{@code ProcessSteerHandler} — directly via
 *       {@link #runTurnNow(String)} from inside its own lane task,
 *       so the same flow applies on the client-driven path.</li>
 * </ul>
 *
 * <p><b>Drain-loop / Auto-Wakeup.</b> The engine's
 * {@link ThinkEngine#runTurn} default keeps re-draining until the
 * queue stays empty across a full pass — messages that arrive during
 * {@code steer} fall into the freshly-emptied queue and are picked
 * up by the next loop iteration in the same lane-turn.
 *
 * <p>{@link ObjectProvider} for {@link ThinkEngineService} because
 * the latter wires through {@code ToolDispatcher → BuiltInToolSource
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
    private final ProgressEmitter progressEmitter;

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

        if (!thinkProcessService.appendPending(parentProcessId, doc, sourceProcessId)) {
            log.warn("Parent notify dropped — parent process not found id='{}' (child='{}', event={})",
                    parentProcessId, sourceProcessId, type);
            return false;
        }
        scheduleTurn(parentProcessId);
        return true;
    }

    /**
     * Submits a {@code runTurn} on the process's own lane — the
     * thread that picks it up will see a serialised view of any
     * other lane work for that process.
     */
    public void scheduleTurn(String processId) {
        laneScheduler.submit(processId, () -> {
            runTurnNow(processId);
            return null;
        });
    }

    /**
     * Invokes the engine's {@link ThinkEngine#runTurn} immediately —
     * the engine drains the persistent inbox itself. Must run on
     * the process's lane (caller's responsibility — typically inside
     * a {@code laneScheduler.submit} block or via
     * {@link #scheduleTurn(String)}).
     */
    public void runTurnNow(String processId) {
        Optional<ThinkProcessDocument> processOpt = thinkProcessService.findById(processId);
        if (processOpt.isEmpty()) {
            log.warn("runTurnNow: process gone id='{}'", processId);
            return;
        }
        ThinkProcessDocument process = processOpt.get();
        // Status-gate: PAUSED / SUSPENDED / CLOSED do not auto-wake even if
        // pending messages have piled up. Resume/Stop is the explicit path
        // back to running. The pending queue itself is still appended to —
        // callers don't lose data, the engine just doesn't drain until
        // resumed. See specification/session-lifecycle.md §3.
        de.mhus.vance.api.thinkprocess.ThinkProcessStatus s = process.getStatus();
        if (s == de.mhus.vance.api.thinkprocess.ThinkProcessStatus.PAUSED
                || s == de.mhus.vance.api.thinkprocess.ThinkProcessStatus.SUSPENDED
                || s == de.mhus.vance.api.thinkprocess.ThinkProcessStatus.CLOSED) {
            log.debug("runTurnNow skipped id='{}' status={}", processId, s);
            return;
        }
        // Engine-lifecycle ping — turn boundaries on the user-progress
        // side-channel so the user sees "[engine_turn_start] ..." in
        // the foot client, parallel to [tool_start] / [tool_end].
        progressEmitter.emitStatus(process, StatusTag.ENGINE_TURN_START,
                process.getName() + " turn start");
        try {
            thinkEngineServiceProvider.getObject().runTurn(process);
        } catch (RuntimeException re) {
            log.warn("runTurn failed id='{}': {}", processId, re.toString(), re);
        } finally {
            progressEmitter.emitStatus(process, StatusTag.ENGINE_TURN_END,
                    process.getName() + " turn end");
        }
    }
}
