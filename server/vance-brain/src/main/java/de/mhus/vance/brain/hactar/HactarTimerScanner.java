package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.shared.hactar.HactarTaskDocument;
import de.mhus.vance.shared.hactar.HactarTaskService;
import de.mhus.vance.shared.hactar.HactarTimerDocument;
import de.mhus.vance.shared.hactar.HactarTimerService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pod-local scheduled scanner for {@code hactar_timers} (plan §6.3).
 * Every {@value #SCAN_INTERVAL_MS}ms it looks up timers with
 * {@code firedAt == null AND fireAt ≤ now}, atomically claims each
 * via {@link HactarTimerService#claimFire}, and publishes a
 * {@link TaskCompletedEvent} for the linked task with the timer's
 * {@code firedOutcome}. The normal completion path then takes over
 * (idempotent {@code appendIfAbsent} drops any race-duplicate from
 * concurrent answer paths, e.g. a user replying on a gate just as the
 * timeout fires).
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class HactarTimerScanner {

    private static final long SCAN_INTERVAL_MS = 5_000L;
    private static final int SCAN_BATCH = 64;

    private final HactarTimerService timerService;
    private final HactarTaskService taskService;
    private final HactarCompletionEventBus eventBus;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = SCAN_INTERVAL_MS)
    public void scan() {
        Instant now = Instant.now();
        List<HactarTimerDocument> due = timerService.findPending(now, SCAN_BATCH);
        if (due.isEmpty()) return;
        for (HactarTimerDocument timer : due) {
            Optional<HactarTimerDocument> claimed = timerService.claimFire(timer.getId(), now);
            if (claimed.isEmpty()) continue; // another pod won
            fire(claimed.get());
        }
    }

    private void fire(HactarTimerDocument timer) {
        Optional<HactarTaskDocument> taskOpt = taskService.findById(timer.getLinkedTaskId());
        if (taskOpt.isEmpty()) {
            log.warn("Hactar timer {} linked task '{}' is gone — discarding",
                    timer.getId(), timer.getLinkedTaskId());
            return;
        }
        HactarTaskDocument task = taskOpt.get();
        eventBus.publish(new TaskCompletedEvent(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                task.getStateName(),
                task.getTaskType() == null ? HactarTaskType.TIMER_TASK : task.getTaskType(),
                timer.getFiredOutcome(),
                /* output */ null,
                /* errorMessage */ null,
                /* durationMs */ 0L,
                /* nextStateOverride */ null));
        log.debug("Hactar timer fired: timer={} task={} outcome={}",
                timer.getId(), task.getId(), timer.getFiredOutcome());
    }
}
