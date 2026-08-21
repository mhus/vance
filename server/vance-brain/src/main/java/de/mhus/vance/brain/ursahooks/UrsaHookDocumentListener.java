package de.mhus.vance.brain.ursahooks;

import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.brain.project.ProjectActivationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Spring listener that refreshes {@link UrsaHookService} whenever a YAML
 * under {@link UrsaHookLoader#HOOK_PATH_ROOT} changes. Replaces the
 * explicit {@code refreshOne(...)} calls that previously lived in the
 * admin/save paths.
 *
 * <p>Hook paths carry two segments after the prefix:
 * {@code _vance/hooks/<event>/<name>.yaml}.
 * {@link UrsaHookLoader#parsePath} returns both at once.
 *
 * <p>Both hard-delete and soft-delete (trash) fire {@code Deleted} events
 * on the bus — {@code DocumentService.trash} publishes against the
 * <em>original</em> path so the listener sees the same {@code _vance/hooks/…}
 * shape regardless of delete flavour.
 *
 * <p>Contract — same rules as the other {@code RoutedDocumentChangedEvent}
 * listeners: idempotent, write-free, swallows own exceptions, path-prefix
 * filter first, no user-scoped mutations — plus <b>activation-gated</b>, for
 * the reason spelled out in {@code UrsaSchedulerDocumentListener}: a hook is a
 * running thing, not a cache, and the router now refreshes the writing pod
 * regardless of who holds the lease.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrsaHookDocumentListener {

    private final UrsaHookService hookService;
    private final ProjectActivationRegistry activationRegistry;

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        String path = event.path();
        if (path == null || !path.startsWith(UrsaHookLoader.HOOK_PATH_ROOT)) {
            return;
        }
        if (!activationRegistry.isActive(event.tenantId(), event.projectId())) {
            // Same reasoning as UrsaSchedulerDocumentListener: a hook
            // registration reacts to events and spawns work, so it must only
            // exist on the pod that activated the project — the router now
            // refreshes the writing pod regardless of ownership.
            log.debug("UrsaHookDocumentListener: '{}/{}' is not active on this pod — "
                            + "ignoring change to '{}'",
                    event.tenantId(), event.projectId(), path);
            return;
        }
        UrsaHookLoader.ParsedPath parsed = UrsaHookLoader.parsePath(path);
        if (parsed == null) return;

        UrsaHookEventName wireEvent = resolveEventName(parsed.event());
        if (wireEvent == null) {
            // Wire-name not in the running brain's enum — could be a
            // forward-compat hook for a future event. Log + drop; the
            // next deploy that knows the event will pick it up on its
            // own bootstrap.
            log.debug("UrsaHookDocumentListener: unknown event name '{}' in path '{}' — ignoring",
                    parsed.event(), path);
            return;
        }
        try {
            hookService.refreshOne(
                    event.tenantId(), event.projectId(), wireEvent, parsed.hookName());
        } catch (RuntimeException ex) {
            log.warn("UrsaHookDocumentListener: refreshOne failed for '{}/{}/{}/{}': {}",
                    event.tenantId(), event.projectId(),
                    parsed.event(), parsed.hookName(), ex.toString());
        }
    }

    private static UrsaHookEventName resolveEventName(String wireName) {
        for (UrsaHookEventName ev : UrsaHookEventName.values()) {
            if (ev.wireName().equals(wireName)) return ev;
        }
        return null;
    }
}
