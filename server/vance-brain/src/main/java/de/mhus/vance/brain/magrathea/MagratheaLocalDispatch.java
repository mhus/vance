package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Hands a freshly inserted task straight to this pod's project lane
 * instead of leaving it for the next {@link MagratheaTaskClaimer} sweep.
 *
 * <p><b>Why this exists.</b> The claimer is a <em>recovery</em> scanner: it
 * exists so a task survives the pod that was supposed to run it dying.
 * Routing every ordinary hand-off through it — insert as {@code PENDING},
 * then rediscover the row two seconds later on the very pod that wrote it —
 * uses a recovery mechanism as the drive train. That costs a full scan
 * interval per state, which is invisible in a nightly automation and very
 * visible in a plan someone is watching run.
 *
 * <p>Correctness is unchanged because this claims through the <em>same</em>
 * atomic CAS ({@link MagratheaTaskService#claim}) the scanner uses: two pods
 * racing on one row still produce exactly one winner, and losing the race
 * here simply means the winner runs it.
 *
 * <p>Everything here is best-effort. A task that is not dispatched — held
 * run, back-off still pending, claim lost, or an outright failure in this
 * class — stays {@code PENDING} in Mongo and the scanner picks it up on its
 * next pass. That is the invariant that lets this be an optimisation rather
 * than a second scheduler: <b>no task depends on the fast path to run.</b>
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@Slf4j
public class MagratheaLocalDispatch {

    private final MagratheaTaskService taskService;
    private final MagratheaProjectLaneManager laneManager;
    private final MagratheaTaskExecutor taskExecutor;
    private final MagratheaPodIdentity podIdentity;

    /**
     * {@code taskExecutor} is {@link Lazy} to break the bean cycle it sits
     * in: the dispatcher owns every {@code MagratheaTypeExecutor}, and
     * {@code WorkflowTaskExecutor} needs {@code MagratheaWorkflowService},
     * which needs this class.
     */
    public MagratheaLocalDispatch(
            MagratheaTaskService taskService,
            MagratheaProjectLaneManager laneManager,
            @Lazy MagratheaTaskExecutor taskExecutor,
            MagratheaPodIdentity podIdentity) {
        this.taskService = taskService;
        this.laneManager = laneManager;
        this.taskExecutor = taskExecutor;
        this.podIdentity = podIdentity;
    }

    /**
     * Claim {@code task} for this pod and queue it on its project lane, if
     * it is runnable right now.
     *
     * <p>Safe to call from the project lane itself: {@code laneManager.submit}
     * appends to the lane queue rather than running inline, so a chain of
     * states advances one lane turn at a time instead of nesting.
     *
     * @param task the row as returned by {@code MagratheaTaskService.insert}
     *             — needs its {@code id} and {@code version}
     */
    public void dispatch(MagratheaTaskDocument task) {
        if (task == null || task.getId() == null) return;

        // HELD means a paused run: the hold is the point, do not run it.
        if (task.getStatus() != MagratheaTaskStatus.PENDING) {
            log.trace("Magrathea task {} not dispatched locally — status {}",
                    task.getId(), task.getStatus());
            return;
        }
        // A retry with back-off is due later; running it now would defeat
        // the back-off. The scanner honours nextAttemptAt, so leave it.
        Instant now = Instant.now();
        if (task.getNextAttemptAt() != null && task.getNextAttemptAt().isAfter(now)) {
            log.trace("Magrathea task {} not dispatched locally — due at {}",
                    task.getId(), task.getNextAttemptAt());
            return;
        }

        try {
            Optional<MagratheaTaskDocument> claimed =
                    taskService.claim(task.getId(), podIdentity.podId(), now);
            if (claimed.isEmpty()) {
                // Another pod's scanner got there first — it will run it.
                log.trace("Magrathea task {} claim lost on local dispatch", task.getId());
                return;
            }
            MagratheaTaskDocument claimedTask = claimed.get();
            laneManager.submit(claimedTask.getProjectId(),
                    () -> taskExecutor.execute(claimedTask));
        } catch (RuntimeException ex) {
            // Never let the fast path break the completion that triggered it:
            // the row is PENDING in Mongo and the scanner is the safety net.
            log.warn("Magrathea local dispatch failed for task {} — leaving it to the claimer: {}",
                    task.getId(), ex.toString());
        }
    }
}
