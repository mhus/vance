package de.mhus.vance.brain.bootstrap;

import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

/**
 * Drains the persistent inbox after a pod restart. For every think-process
 * that has delivered-but-not-drained {@code EngineMessageDocument}s and
 * whose Home Pod is this brain process, schedules a lane turn so the
 * engine catches up on inbox traffic that arrived while the pod was down.
 *
 * <p>Runs after {@link ProjectStartupReclaimer} so reclaimed projects'
 * pod-affinity is up to date before the local-pod filter runs.
 *
 * <p>Outbox-replay (re-pushing sender-side messages that never received
 * an ack) is part of the cross-pod WS path which is not yet wired in v1
 * — see {@code specification/engine-message-routing.md} §8.1. When that
 * lands, an analogous loop over
 * {@link EngineMessageService#findOutboxedBySenders} will live here.
 */
@Service
@RequiredArgsConstructor
@DependsOn("projectStartupReclaimer")
@Slf4j
public class EngineMessageReplayer {

    private final EngineMessageService engineMessageService;
    private final ThinkProcessService thinkProcessService;
    private final ProjectManagerService projectManager;
    private final ProcessEventEmitter eventEmitter;

    @PostConstruct
    void replay() {
        Set<String> pendingTargets = engineMessageService.findPendingTargetProcessIds();
        if (pendingTargets.isEmpty()) {
            log.info("EngineMessageReplayer: no pending inbox to drain");
            return;
        }

        int triggered = 0;
        int skippedRemote = 0;
        int skippedMissing = 0;
        for (String processId : pendingTargets) {
            Optional<ThinkProcessDocument> processOpt = thinkProcessService.findById(processId);
            if (processOpt.isEmpty()) {
                skippedMissing++;
                continue;
            }
            ThinkProcessDocument process = processOpt.get();
            if (!isLocalPodOwning(process)) {
                skippedRemote++;
                continue;
            }
            eventEmitter.scheduleTurn(processId);
            triggered++;
        }
        log.info("EngineMessageReplayer: {} target(s) with pending inbox — {} lane wakeup(s) scheduled, "
                        + "{} skipped (remote pod), {} skipped (process gone)",
                pendingTargets.size(), triggered, skippedRemote, skippedMissing);
    }

    /**
     * A process is "local" when its project's Home Pod is this pod, or
     * when the project hasn't been claimed yet (the bind-handler will
     * claim it on first connect). Either way it's safe to schedule a
     * lane turn here without racing another pod.
     */
    private boolean isLocalPodOwning(ThinkProcessDocument process) {
        Optional<String> endpoint = projectManager.findProjectEndpoint(
                process.getTenantId(), process.getProjectId());
        return endpoint.isEmpty() || projectManager.isLocalPod(endpoint.get());
    }
}
