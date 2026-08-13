package de.mhus.vance.brain.magrathea;

import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTimerDocument;
import de.mhus.vance.shared.magrathea.MagratheaTimerService;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
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
 *
 * <p><b>A missing {@code timeoutSeconds:} is not "no deadline".</b> The
 * three types armed here hand control to something outside the run, and
 * anything outside the run can fail to come back — a subprocess that
 * stops reporting, an inbox item someone deletes, a sub-run orphaned by
 * a crash. Those causes cannot be enumerated, so the deadline cannot be
 * opt-in: an undeclared one falls back to {@link MagratheaProperties}.
 * A fired deadline is <em>recoverable</em> — it carries
 * {@link #OUTCOME_TIMEOUT} into the state's {@code on:}/{@code catch:}
 * routing, so the workflow gets to react rather than just die.
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
    private final MagratheaProperties properties;

    /**
     * Arm the deadline — the declared one, or the configured default for
     * the task type. Only an explicit zero (declared or configured) means
     * "no deadline".
     *
     * <p>Best-effort: a timer that cannot be stored is logged and skipped
     * rather than failing the task. Losing the deadline degrades the task
     * to what it did before this existed; failing the task would throw
     * away work that is already under way. The watchdog is the backstop
     * for exactly this hole.
     */
    public void arm(MagratheaTaskContext context, MagratheaStateSpec state) {
        Duration effective = effectiveTimeout(state);
        if (effective == null || effective.isZero() || effective.isNegative()) return;

        MagratheaTimerDocument timer = MagratheaTimerDocument.builder()
                .tenantId(context.tenantId())
                .projectId(context.projectId())
                .workflowRunId(context.workflowRunId())
                .linkedTaskId(context.taskId())
                .firedOutcome(OUTCOME_TIMEOUT)
                .fireAt(Instant.now().plus(effective))
                .build();
        try {
            timerService.insert(timer);
            log.debug("Magrathea '{}' timeout timer armed fireAt={} ({}declared)",
                    state.name(), timer.getFireAt(),
                    isDeclared(state) ? "" : "un");
        } catch (RuntimeException ex) {
            log.warn("Magrathea '{}' timeout timer insert failed: {} — task continues without deadline",
                    state.name(), ex.getMessage());
        }
    }

    /**
     * A declared {@code timeoutSeconds:} always wins — including
     * {@code 0}, which is how an author says "this one really may wait
     * forever". Only an <em>absent</em> value falls back to the type
     * default. An unknown type gets none: the three armed types are the
     * only ones that reach here, and inventing a deadline for a future
     * fourth would guess at a semantic nobody has written yet.
     */
    @Nullable Duration effectiveTimeout(MagratheaStateSpec state) {
        if (state.timeoutSeconds() != null) return Duration.ofSeconds(state.timeoutSeconds());
        if (state.type() == null) return null;
        return switch (state.type()) {
            case AGENT_TASK -> properties.getDefaultAgentTimeout();
            case GATE_TASK -> properties.getDefaultGateTimeout();
            case WORKFLOW_TASK -> properties.getDefaultSubWorkflowTimeout();
            default -> null;
        };
    }

    private static boolean isDeclared(MagratheaStateSpec state) {
        return state.timeoutSeconds() != null;
    }
}
