package de.mhus.vance.brain.documents.events;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pod-to-pod refresh ingest. Accepts batches sent by other pods'
 * {@link DocumentChangeDispatcher}, validates the payload shape, and
 * re-publishes each event as a local {@link RoutedDocumentChangedEvent}.
 *
 * <p>Authentication: shared {@code X-Vance-Internal-Token} (enforced by
 * {@code InternalAccessFilter} on the {@code /internal/} path prefix). The
 * endpoint has no user-identity surface — see §6 of
 * {@code planning/document-change-events.md}.
 *
 * <p>Always returns 200 on a well-formed body. Listener-side failures are
 * logged locally; we don't propagate them as partial-success because the
 * sender has no useful retry it could perform (Mongo is the source of
 * truth).
 */
@RestController
@RequestMapping("/internal/document")
@RequiredArgsConstructor
@Slf4j
public class DocumentChangedInternalController {

    private final ApplicationEventPublisher publisher;

    @PostMapping("/changed")
    public ResponseEntity<Void> changed(@RequestBody @NotNull HttpDocumentChangedClient.BatchRequest body) {
        if (body == null || body.events() == null || body.events().isEmpty()) {
            return ResponseEntity.ok().build();
        }
        int published = 0;
        for (HttpDocumentChangedClient.WireEvent event : body.events()) {
            RoutedDocumentChangedEvent routed = toRouted(event);
            if (routed == null) {
                log.warn("DocumentChangedInternalController: skipping malformed event {}", event);
                continue;
            }
            try {
                publisher.publishEvent(routed);
                published++;
            } catch (RuntimeException ex) {
                // A listener exception must not poison the rest of the
                // batch. Log + continue with the next event.
                log.warn("DocumentChangedInternalController: listener failed for '{}/{}/{}': {}",
                        event.tenantId(), event.projectId(), event.path(), ex.toString());
            }
        }
        log.debug("DocumentChangedInternalController: ingested batch of {} (published {})",
                body.events().size(), published);
        return ResponseEntity.ok().build();
    }

    private static RoutedDocumentChangedEvent toRouted(HttpDocumentChangedClient.WireEvent e) {
        if (e == null
                || e.op() == null
                || e.tenantId() == null || e.tenantId().isBlank()
                || e.projectId() == null || e.projectId().isBlank()
                || e.path() == null || e.path().isBlank()) {
            return null;
        }
        return switch (e.op()) {
            case "UPSERT" -> new RoutedDocumentChangedEvent.Upserted(
                    e.tenantId(), e.projectId(), e.path(),
                    e.documentId() == null ? "" : e.documentId());
            case "DELETE" -> new RoutedDocumentChangedEvent.Deleted(
                    e.tenantId(), e.projectId(), e.path(), e.documentId());
            default -> null;
        };
    }
}
