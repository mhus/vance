package de.mhus.vance.brain.magrathea;

import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTimerDocument;
import de.mhus.vance.shared.magrathea.MagratheaTimerService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Arms the state's {@code timeoutSeconds:} for the task types that hand
 * control to something else and wait — gate (a person), agent (a
 * ThinkProcess), sub-workflow (another run).
 *
 * <p>The synchronous types need none of this: {@code shell_task} and
 * {@code script_task} pass the timeout straight into the executor they
 * block on. The asynchronous ones have nothing to block on, so the
 * deadline has to become a timer that the scanner fires — and if it is
 * not armed, the declared {@code timeoutSeconds:} is decoration. That is
 * how a stuck {@code agent_task} could sit forever with a 900-second
 * timeout written right above it.
 *
 * <p>Two racing paths from here on: the real completion and the timer.
 * Whoever lands first wins through the {@code appendIfAbsent} on the
 * task-result record; the loser is dropped as a duplicate.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaTimeoutScheduler {

    /** Outcome a fired timeout carries; routed via {@code catch:}. */
    public static final String OUTCOME_TIMEOUT = "timeout";

    private final MagratheaTimerService timerService;

    /**
     * Arm the deadline, if the state declares one. No-op for a missing or
     * non-positive {@code timeoutSeconds}.
     *
     * <p>Best-effort: a timer that cannot be stored is logged and skipped
     * rather than failing the task. Losing the deadline degrades the task
     * to what it did before this existed; failing the task would throw
     * away work that is already under way.
     */
    public void arm(MagratheaTaskContext context, MagratheaStateSpec state) {
        Integer timeoutSeconds = state.timeoutSeconds();
        if (timeoutSeconds == null || timeoutSeconds <= 0) return;

        MagratheaTimerDocument timer = MagratheaTimerDocument.builder()
                .tenantId(context.tenantId())
                .projectId(context.projectId())
                .workflowRunId(context.workflowRunId())
                .linkedTaskId(context.taskId())
                .firedOutcome(OUTCOME_TIMEOUT)
                .fireAt(Instant.now().plusSeconds(timeoutSeconds))
                .build();
        try {
            timerService.insert(timer);
            log.debug("Magrathea '{}' timeout timer armed fireAt={}", state.name(), timer.getFireAt());
        } catch (RuntimeException ex) {
            log.warn("Magrathea '{}' timeout timer insert failed: {} — task continues without deadline",
                    state.name(), ex.getMessage());
        }
    }
}
