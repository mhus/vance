package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Maps life-cycle status transitions of a child process to a
 * {@code PROCESS_EVENT} on its parent's pending queue. The actual
 * appending + lane-wakeup is delegated to {@link ProcessEventEmitter}
 * so this class stays focused on the filter rules.
 *
 * <p>Filter:
 * <ul>
 *   <li>Skip top-level processes (no {@code parentProcessId}).</li>
 *   <li>Skip status repaints — same prior and new status.</li>
 *   <li>Skip intermediate transitions ({@code READY/RUNNING/PAUSED/SUSPENDED})
 *       — they're internal lane state, not something the parent needs
 *       to be woken for.</li>
 *   <li>Skip parent-initiated stops — when the parent itself called
 *       {@code process_stop} via {@link de.mhus.vance.brain.tools.process.ProcessStopTool},
 *       the resulting STOPPED event would loop back to it as redundant
 *       inbox material. {@link StopInitiatorRegistry} carries the
 *       initiator id so we can detect and suppress this case. Without
 *       the suppression, Arthur — and any other orchestrator — gets a
 *       phantom turn that the LLM sometimes interprets as "do
 *       something" (the classic spontaneous-restart symptom).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ParentNotificationListener {

    private final ProcessEventEmitter eventEmitter;
    private final ThinkProcessService thinkProcessService;
    /**
     * Lazy because {@code ThinkEngineService} pulls in tools/recipes
     * which transitively reach this listener — direct dependency
     * would close the bean-graph cycle.
     */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final StopInitiatorRegistry stopInitiatorRegistry;

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        String parentId = event.parentProcessId();
        if (parentId == null) {
            return;
        }
        if (event.priorStatus() == event.newStatus()) {
            return;
        }
        ProcessEventType eventType = mapStatus(event.newStatus());
        if (eventType == null) {
            return;
        }
        // Parent-initiated stops loop right back to the caller — suppress.
        if (eventType == ProcessEventType.STOPPED) {
            String initiator = stopInitiatorRegistry.consume(event.processId()).orElse(null);
            if (initiator != null && initiator.equals(parentId)) {
                log.debug("Parent {} stopped child {} itself — suppressing STOPPED notification",
                        parentId, event.processId());
                return;
            }
        }
        ParentReport report = buildReport(event.processId(), eventType, event.newStatus());
        boolean queued = eventEmitter.notifyParent(
                parentId,
                event.processId(),
                eventType,
                report.humanSummary(),
                report.payload());
        if (queued) {
            log.info("Parent notify queued parent='{}' child='{}' event={}",
                    parentId, event.processId(), eventType);
        }
    }

    /**
     * Asks the child's engine for its parent-report. Falls back to
     * a generic "child status" line if the engine throws or the
     * process row is gone — never let a hook failure swallow the
     * parent-notification.
     */
    private ParentReport buildReport(
            String childProcessId,
            ProcessEventType eventType,
            ThinkProcessStatus newStatus) {
        Optional<ThinkProcessDocument> processOpt =
                thinkProcessService.findById(childProcessId);
        if (processOpt.isEmpty()) {
            return ParentReport.of(genericSummary(childProcessId, newStatus));
        }
        ThinkProcessDocument process = processOpt.get();
        try {
            ThinkEngine engine = thinkEngineServiceProvider.getObject()
                    .resolveForProcess(process);
            return engine.summarizeForParent(process, eventType);
        } catch (RuntimeException e) {
            log.warn("summarizeForParent failed for child='{}' engine='{}': {}",
                    childProcessId, process.getThinkEngine(), e.toString());
            return ParentReport.of(genericSummary(childProcessId, newStatus));
        }
    }

    private static @Nullable ProcessEventType mapStatus(ThinkProcessStatus status) {
        return switch (status) {
            case DONE -> ProcessEventType.DONE;
            case BLOCKED -> ProcessEventType.BLOCKED;
            case STOPPED -> ProcessEventType.STOPPED;
            case STALE -> ProcessEventType.FAILED;
            case READY, RUNNING, PAUSED, SUSPENDED -> null;
        };
    }

    private static String genericSummary(String childProcessId, ThinkProcessStatus status) {
        return "Child process " + childProcessId + " status=" + status.name().toLowerCase();
    }
}
