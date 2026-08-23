package de.mhus.vance.brain.ursahooks;

import de.mhus.vance.api.action.TriggerKind;
import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import de.mhus.vance.shared.inbox.MaximegalonCreatedEvent;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Forwards {@link MaximegalonCreatedEvent} into the hook stream as
 * {@code inbox.item.created}. The inbox item does not carry the
 * project id directly (it's per-session), so the listener resolves it
 * via the session's home project — when neither session nor project
 * is available the event is dropped silently (a tool-driven item
 * without scope can't fan out to project-scoped hooks).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrsaHookInboxLifecycleListener {

    private final ApplicationEventPublisher publisher;
    private final SessionService sessionService;
    private final de.mhus.vance.shared.thinkprocess.ThinkProcessService thinkProcessService;

    @EventListener
    public void onCreated(MaximegalonCreatedEvent event) {
        MaximegalonDocument item = event.item();
        // Cycle guard (mirror UrsaHookProcessLifecycleListener): if this inbox
        // item was created by a hook-spawned process, do NOT re-fire
        // inbox.item.created — a hook on inbox.item.created whose action posts an
        // inbox item would otherwise loop forever (no depth bound). Scheduler/
        // event/user/tool origins are not HOOK-tagged and fan out normally.
        String originProcessId = item.getOriginProcessId();
        if (originProcessId != null && !originProcessId.isBlank()) {
            boolean hookSpawned = thinkProcessService.findById(originProcessId)
                    .map(ThinkProcessDocument::getTriggerOrigin)
                    .map(origin -> origin.getKind() == TriggerKind.HOOK)
                    .orElse(false);
            if (hookSpawned) {
                log.debug("Skipping inbox.item.created fire for hook-spawned origin "
                        + "process id='{}'", originProcessId);
                return;
            }
        }
        @org.jspecify.annotations.Nullable
        String projectId = resolveProjectId(item);
        if (projectId == null) return;

        Map<String, Object> itemPayload = new LinkedHashMap<>();
        itemPayload.put("id", item.getId());
        itemPayload.put("type", item.getType() == null ? null : item.getType().name());
        itemPayload.put("title", item.getTitle());
        itemPayload.put("body", item.getBody());
        itemPayload.put("criticality",
                item.getCriticality() == null ? null : item.getCriticality().name());
        itemPayload.put("tags", item.getTags() == null ? List.of() : List.copyOf(item.getTags()));
        itemPayload.put("requiresAction", item.isRequiresAction());
        itemPayload.put("recipientUserId", item.getAssignedToUserId());
        itemPayload.put("originatorUserId", item.getOriginatorUserId());
        if (item.getOriginProcessId() != null) {
            itemPayload.put("processId", item.getOriginProcessId());
        }
        if (item.getOriginSessionId() != null) {
            itemPayload.put("sessionId", item.getOriginSessionId());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("item", itemPayload);

        publisher.publishEvent(UrsaHookFireableEvent.of(
                item.getTenantId(), projectId,
                UrsaHookEventName.INBOX_ITEM_CREATED, payload));
    }

    private @org.jspecify.annotations.Nullable String resolveProjectId(MaximegalonDocument item) {
        String sessionId = item.getOriginSessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        Optional<SessionDocument> session = sessionService.findBySessionId(sessionId);
        return session.map(SessionDocument::getProjectId).orElse(null);
    }
}
