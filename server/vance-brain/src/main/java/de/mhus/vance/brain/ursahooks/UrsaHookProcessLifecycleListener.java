package de.mhus.vance.brain.ursahooks;

import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridges {@link ThinkProcessStatusChangedEvent} into the hook event
 * stream. Maps {@code CloseReason.DONE} → {@code process.completed}
 * and every other terminal reason → {@code process.failed}.
 *
 * <p>The listener runs synchronously on Spring's event-thread, then
 * publishes a {@link UrsaHookFireableEvent} that the {@link UrsaHookDispatcher}
 * picks up asynchronously. That two-step is intentional: the
 * publisher pays a few microseconds per status change even when no
 * hooks are registered, but the actual hook work happens off the
 * critical path.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrsaHookProcessLifecycleListener {

    private final ApplicationEventPublisher publisher;
    private final ThinkProcessService thinkProcessService;

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        if (event.newStatus() != ThinkProcessStatus.CLOSED) {
            return;
        }
        Optional<ThinkProcessDocument> opt = thinkProcessService.findById(event.processId());
        if (opt.isEmpty()) return;
        ThinkProcessDocument doc = opt.get();
        CloseReason reason = doc.getCloseReason();

        UrsaHookEventName hookEvent = (reason == CloseReason.DONE)
                ? UrsaHookEventName.PROCESS_COMPLETED
                : UrsaHookEventName.PROCESS_FAILED;

        Map<String, Object> processPayload = new LinkedHashMap<>();
        processPayload.put("id", doc.getId());
        processPayload.put("name", doc.getName());
        processPayload.put("title", doc.getTitle());
        processPayload.put("sessionId", doc.getSessionId());
        processPayload.put("engine", doc.getThinkEngine());
        processPayload.put("recipe", doc.getRecipeName());
        processPayload.put("closeReason", reason == null ? null : reason.name());
        processPayload.put("status", doc.getStatus() == null ? null : doc.getStatus().name());
        if (doc.getParentProcessId() != null) {
            processPayload.put("parentProcessId", doc.getParentProcessId());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("process", processPayload);

        publisher.publishEvent(UrsaHookFireableEvent.of(
                event.tenantId(), doc.getProjectId(), hookEvent, payload));
    }
}
