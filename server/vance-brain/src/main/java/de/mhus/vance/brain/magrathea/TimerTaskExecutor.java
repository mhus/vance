package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaTimerDocument;
import de.mhus.vance.shared.magrathea.MagratheaTimerService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Delay executor (plan §4.5). Registers a timer in
 * {@code magrathea_timers}, marks the task as
 * {@code WAITING_SUBPROCESS}-equivalent through the timer link, and
 * returns asynchronous — the {@link MagratheaTimerScanner} fires the
 * {@link TaskCompletedEvent} with outcome {@code "fired"} when
 * {@code fireAt} arrives.
 *
 * <h3>YAML</h3>
 * <pre>
 * wait_for_user_feedback:
 *   type: timer_task
 *   duration: "7d"          # ISO-8601 (PT7D) or shortcut (7d/4h/30m/45s)
 *   on:
 *     fired: send_reminder
 * </pre>
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class TimerTaskExecutor implements MagratheaTypeExecutor {

    public static final String OUTCOME_FIRED = "fired";
    private static final String SPEC_DURATION = "duration";

    private final MagratheaTimerService timerService;
    private final MagratheaTaskService taskService;

    @Override
    public MagratheaTaskType type() {
        return MagratheaTaskType.TIMER_TASK;
    }

    @Override
    public Optional<TaskOutcome> execute(MagratheaTaskContext context) {
        MagratheaStateSpec state = context.state();
        String durationStr = state.specString(SPEC_DURATION);
        if (durationStr == null) {
            return Optional.of(TaskOutcome.failure(
                    "timer_task '" + state.name() + "' is missing required 'duration:'"));
        }
        Duration duration;
        try {
            duration = MagratheaDurations.parse(durationStr);
        } catch (IllegalArgumentException ex) {
            return Optional.of(TaskOutcome.failure(
                    "timer_task '" + state.name() + "': " + ex.getMessage()));
        }

        Instant fireAt = Instant.now().plus(duration);
        MagratheaTimerDocument timer = MagratheaTimerDocument.builder()
                .tenantId(context.tenantId())
                .projectId(context.projectId())
                .workflowRunId(context.workflowRunId())
                .linkedTaskId(context.taskId())
                .firedOutcome(OUTCOME_FIRED)
                .fireAt(fireAt)
                .build();

        MagratheaTimerDocument inserted;
        try {
            inserted = timerService.insert(timer);
        } catch (RuntimeException ex) {
            log.warn("Magrathea timer_task '{}' insert failed: {}", state.name(), ex.getMessage());
            return Optional.of(TaskOutcome.failure(
                    "Timer insert failed: " + ex.getMessage()));
        }
        taskService.linkTimer(context.taskId(), inserted.getId());

        log.debug("Magrathea timer_task '{}' scheduled fireAt={} timerId={}",
                state.name(), fireAt, inserted.getId());
        return Optional.empty();
    }
}
