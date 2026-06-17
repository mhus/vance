package de.mhus.vance.brain.ursascheduler;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.shared.ursascheduler.UrsaSchedulerLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Spring listener that refreshes {@link UrsaSchedulerService} whenever a
 * YAML under {@link UrsaSchedulerLoader#SCHEDULER_PATH_PREFIX} changes —
 * regardless of which write path produced the change (UrsaScheduler admin
 * controller, scheduler tool calls, raw {@code DocumentService.upsertText}
 * from the Documents-Editor, Kit installer). Replaces the explicit
 * {@code schedulerService.refreshOne(...)} calls that the admin layers
 * used to make.
 *
 * <p>Contract (mirrors {@link RoutedDocumentChangedEvent} listener rules):
 * <ul>
 *   <li>Idempotent — {@code refreshOne} is the same call however many
 *       times we fire it.</li>
 *   <li>Write-free — only mutates the in-memory registry, never reaches
 *       back into {@code DocumentService}.</li>
 *   <li>Catches its own {@code RuntimeException}s — a malformed YAML on
 *       disk must not unwind the publisher's write.</li>
 *   <li>Path-prefix gated so the bulk of fires return early.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrsaSchedulerDocumentListener {

    private final UrsaSchedulerService schedulerService;

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        String path = event.path();
        if (path == null || !path.startsWith(UrsaSchedulerLoader.SCHEDULER_PATH_PREFIX)) {
            return;
        }
        String name = UrsaSchedulerLoader.nameFromPath(path);
        if (name == null) return;
        try {
            schedulerService.refreshOne(event.tenantId(), event.projectId(), name);
        } catch (RuntimeException ex) {
            log.warn("UrsaSchedulerDocumentListener: refreshOne failed for '{}/{}/{}': {}",
                    event.tenantId(), event.projectId(), name, ex.toString());
        }
    }
}
