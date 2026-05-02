package de.mhus.vance.brain.bootstrap;

import de.mhus.vance.brain.enginemessage.EngineWsAck;
import de.mhus.vance.brain.enginemessage.EngineWsClient;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

/**
 * Two boot-time recovery passes for the {@code engine_messages} collection.
 *
 * <h2>Inbox-drain</h2>
 * For every think-process with delivered-but-not-drained messages and
 * whose Home Pod is this brain process, schedules a lane turn so the
 * engine catches up on inbox traffic that arrived while the pod was down.
 *
 * <h2>Outbox-replay</h2>
 * For every outboxed (= {@code deliveredAt == null}) message whose
 * <em>sender</em>'s Home Pod is this brain process, re-pushes the
 * frame through {@link EngineWsClient} so a sender that crashed
 * mid-push picks up where it left off. Receiver-side dedup keeps a
 * second push idempotent. Messages whose sender lives elsewhere are
 * the other pod's responsibility and are skipped here.
 *
 * <p>Runs after {@link ProjectStartupReclaimer} so reclaimed projects'
 * pod-affinity is up to date before the "is this mine" filter runs.
 */
@Service
@RequiredArgsConstructor
@DependsOn("projectStartupReclaimer")
@Slf4j
public class EngineMessageReplayer {

    private static final Duration WS_REPLAY_TIMEOUT = Duration.ofSeconds(10);

    private final EngineMessageService engineMessageService;
    private final ThinkProcessService thinkProcessService;
    private final ProjectManagerService projectManager;
    private final ProcessEventEmitter eventEmitter;
    private final EngineWsClient engineWsClient;

    @PostConstruct
    void replay() {
        replayInbox();
        replayOutbox();
    }

    /**
     * Inbox-drain pass — visible for tests that need to trigger a replay
     * outside of the boot lifecycle. Idempotent: messages already drained
     * stay drained, fresh inbox entries get a lane wakeup. Safe to call
     * any number of times.
     */
    public void replayInbox() {
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
     * Outbox-replay pass — visible for tests. Looks at every outboxed
     * message globally, replays the ones whose sender lives on this
     * pod, and leaves the others to their owning pods.
     */
    public void replayOutbox() {
        List<EngineMessageDocument> outboxed = engineMessageService.findAllOutboxed();
        if (outboxed.isEmpty()) {
            log.info("EngineMessageReplayer: no outboxed messages to replay");
            return;
        }

        int replayed = 0;
        int skippedRemoteSender = 0;
        int skippedNoSender = 0;
        int failedNoTarget = 0;
        int failedPush = 0;
        for (EngineMessageDocument msg : outboxed) {
            if (msg.getSenderProcessId() == null || msg.getSenderProcessId().isBlank()) {
                // Outbox rows from the cross-pod path always carry a sender.
                // Anything with a blank sender is either legacy or corrupt;
                // surface it but don't stall the replay loop.
                skippedNoSender++;
                continue;
            }
            Optional<ThinkProcessDocument> senderOpt = thinkProcessService.findById(msg.getSenderProcessId());
            if (senderOpt.isEmpty() || !isLocalPodOwning(senderOpt.get())) {
                skippedRemoteSender++;
                continue;
            }

            Optional<ThinkProcessDocument> targetOpt = thinkProcessService.findById(msg.getTargetProcessId());
            if (targetOpt.isEmpty()) {
                log.warn("Outbox-replay: target process {} for messageId={} is gone — leaving in outbox",
                        msg.getTargetProcessId(), msg.getMessageId());
                failedNoTarget++;
                continue;
            }
            Optional<String> targetHome = projectManager.findProjectEndpoint(
                    targetOpt.get().getTenantId(), targetOpt.get().getProjectId());
            if (targetHome.isEmpty()) {
                log.warn("Outbox-replay: target {}'s project not yet claimed — leaving in outbox",
                        msg.getTargetProcessId());
                failedNoTarget++;
                continue;
            }
            if (projectManager.isLocalPod(targetHome.get())) {
                // Same-pod replay shouldn't normally exist (the local-direct
                // path doesn't go through the outbox), but if a row got here
                // anyway it's safe to deliver it directly.
                engineMessageService.acceptDelivery(msg);
                eventEmitter.scheduleTurn(msg.getTargetProcessId());
                replayed++;
                continue;
            }

            try {
                EngineWsAck ack = engineWsClient.send(targetHome.get(), msg, WS_REPLAY_TIMEOUT);
                if (EngineWsAck.STATUS_ACK.equals(ack.status())) {
                    replayed++;
                } else {
                    log.warn("Outbox-replay: receiver rejected messageId={}: {}",
                            msg.getMessageId(), ack.reason());
                    failedPush++;
                }
            } catch (RuntimeException e) {
                log.warn("Outbox-replay: WS push failed for messageId={} target={} via {}: {}",
                        msg.getMessageId(), msg.getTargetProcessId(), targetHome.get(), e.toString());
                failedPush++;
            }
        }
        log.info("EngineMessageReplayer: {} outboxed message(s) — {} re-pushed, "
                        + "{} skipped (remote sender), {} skipped (no sender), "
                        + "{} target gone, {} push failed",
                outboxed.size(), replayed, skippedRemoteSender, skippedNoSender,
                failedNoTarget, failedPush);
    }

    /**
     * A process is "local" when its project's Home Pod is this pod, or
     * when the project hasn't been claimed yet (the bind-handler will
     * claim it on first connect). Either way it's safe to schedule a
     * lane turn / replay a send from here without racing another pod.
     */
    private boolean isLocalPodOwning(ThinkProcessDocument process) {
        Optional<String> endpoint = projectManager.findProjectEndpoint(
                process.getTenantId(), process.getProjectId());
        return endpoint.isEmpty() || projectManager.isLocalPod(endpoint.get());
    }
}
