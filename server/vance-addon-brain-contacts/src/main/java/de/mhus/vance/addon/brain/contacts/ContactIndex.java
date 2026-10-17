package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.KindCodecException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Parsed address book per project — a cache, not a store. The truth is the
 * documents under {@code _vance/contacts/}; a snapshot is rebuilt from them on
 * first use and dropped whenever one of them changes.
 *
 * <p>Two invalidation paths: the routed document-change event (covers writes
 * from WebDAV, kits, archive restores and — for {@code _tenant} — other pods),
 * and a short time-to-live as the backstop for the one case the router does
 * not reach: a pod that neither wrote nor owns a regular project yet answered
 * a read for it. Without the TTL such a pod would serve a stale list until
 * restart.
 */
@Component
@Slf4j
public class ContactIndex {

    static final Duration TTL = Duration.ofSeconds(60);

    private final DocumentService documentService;
    private final Clock clock;
    private final Map<Key, Snapshot> snapshots = new ConcurrentHashMap<>();

    /** Spring's constructor — the clock is only replaced in tests. */
    @Autowired
    public ContactIndex(DocumentService documentService) {
        this(documentService, Clock.systemUTC());
    }

    ContactIndex(DocumentService documentService, Clock clock) {
        this.documentService = documentService;
        this.clock = clock;
    }

    /** One readable card file of a project. */
    record Indexed(
            String id,
            String documentId,
            ContactCard card,
            @Nullable String photoPath,
            @Nullable Long photoVersion) {

        ContactEntry as(ContactScope scope, String projectId) {
            return new ContactEntry(id, scope, projectId, documentId, card, photoPath, photoVersion);
        }
    }

    /**
     * @param broken paths of card files that did not parse — reported, never
     *               silently counted as "no contacts"
     */
    record Snapshot(Map<String, Indexed> byId, List<String> broken, Instant builtAt) {}

    private record Key(String tenantId, String projectId) {}

    /** The cards of one project, labelled with the scope the caller reads it as. */
    public List<ContactEntry> entries(String tenantId, String projectId, ContactScope scope) {
        List<ContactEntry> out = new ArrayList<>();
        for (Indexed i : snapshot(tenantId, projectId).byId().values()) out.add(i.as(scope, projectId));
        return out;
    }

    public @Nullable ContactEntry find(String tenantId, String projectId, ContactScope scope, String id) {
        Indexed i = snapshot(tenantId, projectId).byId().get(id);
        return i == null ? null : i.as(scope, projectId);
    }

    public List<String> broken(String tenantId, String projectId) {
        return snapshot(tenantId, projectId).broken();
    }

    public void invalidate(String tenantId, String projectId) {
        snapshots.remove(new Key(tenantId, projectId));
    }

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        // Cache only — idempotent, write-free, never throws (listener contract).
        if (ContactIds.inFolder(event.path())) {
            invalidate(event.tenantId(), event.projectId());
        }
    }

    Snapshot snapshot(String tenantId, String projectId) {
        Key key = new Key(tenantId, projectId);
        Snapshot current = snapshots.get(key);
        if (current != null && current.builtAt().plus(TTL).isAfter(clock.instant())) return current;
        Snapshot fresh = build(tenantId, projectId);
        snapshots.put(key, fresh);
        return fresh;
    }

    private Snapshot build(String tenantId, String projectId) {
        List<DocumentDocument> docs = documentService.listUnderFolder(tenantId, projectId, ContactIds.FOLDER);
        Map<String, DocumentDocument> photos = new HashMap<>();
        List<DocumentDocument> cards = new ArrayList<>();
        for (DocumentDocument d : docs) {
            String rest = d.getPath().substring(ContactIds.FOLDER.length());
            if (rest.contains("/")) continue;
            if (ContactIds.idOfCardPath(d.getPath()) != null) {
                cards.add(d);
            } else {
                photos.put(rest, d);
            }
        }
        Map<String, Indexed> byId = new java.util.LinkedHashMap<>();
        List<String> broken = new ArrayList<>();
        for (DocumentDocument d : cards) {
            String id = ContactIds.idOfCardPath(d.getPath());
            if (id == null || d.getId() == null) continue;
            ContactCard card;
            try {
                card = ContactCodec.parseCard(documentService.readContent(d));
            } catch (KindCodecException | IllegalArgumentException e) {
                log.trace("ContactIndex: unreadable card {}/{}: {}", projectId, d.getPath(), e.getMessage());
                broken.add(d.getPath());
                continue;
            }
            DocumentDocument photo = card.photo() == null ? null : photos.get(card.photo());
            byId.put(
                    id,
                    new Indexed(
                            id,
                            d.getId(),
                            card,
                            photo == null ? null : photo.getPath(),
                            photo == null ? null : photo.getVersion()));
        }
        return new Snapshot(Map.copyOf(byId), List.copyOf(broken), clock.instant());
    }
}
