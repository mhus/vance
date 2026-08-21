package de.mhus.vance.brain.ursascheduler;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.brain.project.ProjectActivationRegistry;
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
 *   <li><b>Activation-gated</b> — see below.</li>
 * </ul>
 *
 * <p><b>Why activation matters here and not in the cache listeners.</b> Since
 * the router refreshes the writing pod unconditionally
 * ({@code planning/project-ownership-lease-design.md} §7), a listener can be
 * called on a pod that has nothing to do with the project. For a cache that is
 * harmless — {@code ServerToolRegistry.refreshOne} no-ops when the scope is not
 * loaded. A scheduler registration is not a cache: it arms a
 * {@code ScheduledFuture} that spawns processes. Registering one here would
 * give a project two live crons on two pods, which is exactly what the local
 * {@code TaskScheduler} design excludes by assuming one active pod per project
 * ({@code specification/public/scheduler.md} §12).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrsaSchedulerDocumentListener {

    private final UrsaSchedulerService schedulerService;
    private final ProjectActivationRegistry activationRegistry;

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        String path = event.path();
        if (path == null || !path.startsWith(UrsaSchedulerLoader.SCHEDULER_PATH_PREFIX)) {
            return;
        }
        if (!activationRegistry.isActive(event.tenantId(), event.projectId())) {
            // A scheduler registration is a running thing, not a cache: it
            // arms a ScheduledFuture that will spawn processes. Creating one
            // because this pod happened to serve the document write would put
            // a second pod's cron on the same project — exactly the
            // double-firing the local TaskScheduler design rules out by
            // assuming one active pod per project
            // ({@code specification/public/scheduler.md} §12).
            log.debug("UrsaSchedulerDocumentListener: '{}/{}' is not active on this pod — "
                            + "ignoring change to '{}'",
                    event.tenantId(), event.projectId(), path);
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
