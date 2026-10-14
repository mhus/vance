package de.mhus.vance.brain.trillian;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The bookkeeping round for the conversation heads of multi-headed
 * Trillians — the single closer of heads and the mechanical end of
 * conversations: threads that ended take their heads down, unanswered
 * contributions get a reminder and then a mechanical fallback, the message
 * limit and the inactivity window close threads that nobody else closes.
 * See {@code TrillianHeadService#sweepLoop} for what one round does.
 *
 * <p><b>The hub's home pod sweeps its own Trillians</b>, same shape as
 * {@link TrillianHeartbeatTick}: heads live in the hub, the sweep's writes
 * (close, nudge, archive) are plain Mongo writes from any pod, but one pod
 * per Trillian keeps the rounds from piling up on the same rows. A pod that
 * loses a hub drops out; the next holder continues.
 *
 * <p><b>Cross-pod-safe by design:</b> unlike the contribution dispatch
 * (routed through the engine-message router to wherever the head is), the
 * sweep acts on Mongo state directly — a head that just moved pods is
 * found by the new holder on its next round. That is the same resilience
 * the wakeup machinery has: due facts live on documents, not on pods.
 */
@Component
@ConditionalOnProperty(value = "vance.trillian.head.sweep-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class TrillianHeadSweepTick {

    private final ClusterService clusterService;
    private final ProjectService projectService;
    private final TrillianWakeupService wakeupService;
    private final TrillianNatureRegistry natureRegistry;
    private final TrillianHeadService headService;

    @Scheduled(
            fixedDelayString = "${vance.trillian.head.sweep-interval-ms:60000}",
            initialDelayString = "${vance.trillian.head.sweep-interval-ms:60000}")
    public void tick() {
        Instant now = Instant.now();
        List<ProjectDocument> held = projectService.findRunningByHomePodId(clusterService.selfPodId());
        if (held.isEmpty()) {
            return;
        }
        int swept = 0;
        for (ThinkProcessDocument loop : wakeupService.liveLoopsIn(held)) {
            try {
                TrillianNature nature = natureRegistry.resolve(TrillianSessionBootstrapper.readNature(loop));
                if (!nature.multiHead()) {
                    continue; // single-headed: no heads, nothing to sweep
                }
                headService.sweepLoop(loop, now);
                swept++;
            } catch (RuntimeException e) {
                log.warn("Trillian head sweep: loop '{}' failed: {}", loop.getId(), e.toString());
            }
        }
        log.trace("Trillian head sweep node='{}' multiHeadLoops={}", clusterService.selfNodeName(), swept);
    }
}
