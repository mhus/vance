package de.mhus.vance.brain.ursahooks;

import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
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
 * <p>Contract — same five rules as the other {@code RoutedDocumentChangedEvent}
 * listeners: idempotent, write-free, swallows own exceptions, path-prefix
 * filter first, no user-scoped mutations.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrsaHookDocumentListener {

    private final UrsaHookService hookService;

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        String path = event.path();
        if (path == null || !path.startsWith(UrsaHookLoader.HOOK_PATH_ROOT)) {
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
