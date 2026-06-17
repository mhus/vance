package de.mhus.vance.brain.servertool;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.shared.servertool.ServerToolLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Spring listener that refreshes the {@link ServerToolRegistry} whenever a
 * YAML under {@link ServerToolLoader#SERVER_TOOL_PATH_PREFIX} changes —
 * regardless of which write path produced the change (Tools-Editor admin
 * controller, Documents-Editor raw write, Kit installer, direct
 * {@code DocumentService.upsertText}). Replaces the explicit
 * {@code registry.refreshOne(...)} calls that previously had to be
 * sprinkled into every admin service.
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
public class ServerToolDocumentListener {

    private final ServerToolRegistry registry;

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        String path = event.path();
        if (path == null || !path.startsWith(ServerToolLoader.SERVER_TOOL_PATH_PREFIX)) {
            return;
        }
        String name = ServerToolLoader.nameFromPath(path);
        if (name == null) return;
        try {
            registry.refreshOne(event.tenantId(), event.projectId(), name);
        } catch (RuntimeException ex) {
            log.warn("ServerToolDocumentListener: refreshOne failed for '{}/{}/{}': {}",
                    event.tenantId(), event.projectId(), name, ex.toString());
        }
    }
}
