package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The cache rules: TTL, invalidation, no stale store after a concurrent invalidation, eviction. */
class ContactIndexTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "website";

    private final MutableClock clock = new MutableClock();
    private final Map<String, List<DocumentDocument>> folders = new HashMap<>();
    private final Map<String, String> bodies = new HashMap<>();
    private DocumentService documentService;
    private ContactIndex index;
    private Runnable duringList = () -> {};

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        when(documentService.listUnderFolder(eq(TENANT), anyString(), eq(ContactIds.FOLDER)))
                .thenAnswer(inv -> {
                    List<DocumentDocument> snapshot = List.copyOf(folders.getOrDefault(inv.getArgument(1), List.of()));
                    Runnable hook = duringList;
                    duringList = () -> {};
                    hook.run();
                    return snapshot;
                });
        when(documentService.readContent(any(DocumentDocument.class)))
                .thenAnswer(inv -> bodies.get(((DocumentDocument) inv.getArgument(0)).getId()));
        index = new ContactIndex(documentService, clock);
    }

    private String store(String project, String name) {
        String id = ContactIds.newId();
        DocumentDocument d = new DocumentDocument();
        d.setId("doc-" + id);
        d.setProjectId(project);
        d.setPath(ContactIds.cardPath(id));
        folders.computeIfAbsent(project, p -> new ArrayList<>()).add(d);
        bodies.put(
                d.getId(),
                ContactCodec.serialize(ContactCard.builder().fullName(name).build()));
        return id;
    }

    private List<String> names() {
        return index.entries(TENANT, PROJECT, ContactScope.PROJECT).stream()
                .map(e -> e.card().displayName())
                .toList();
    }

    @Test
    void servesFromCacheWithinTheTtlAndRebuildsAfter() {
        store(PROJECT, "Anna");
        assertThat(names()).containsExactly("Anna");
        store(PROJECT, "Bob");

        clock.advance(ContactIndex.TTL.minusSeconds(1));
        assertThat(names()).containsExactly("Anna");

        clock.advance(Duration.ofSeconds(2));
        assertThat(names()).containsExactlyInAnyOrder("Anna", "Bob");
        verify(documentService, times(2)).listUnderFolder(TENANT, PROJECT, ContactIds.FOLDER);
    }

    @Test
    void invalidationForcesARebuild() {
        store(PROJECT, "Anna");
        assertThat(names()).containsExactly("Anna");
        store(PROJECT, "Bob");

        index.invalidate(TENANT, PROJECT);

        assertThat(names()).containsExactlyInAnyOrder("Anna", "Bob");
    }

    @Test
    void aBuildOvertakenByAnInvalidationIsNotKept() {
        store(PROJECT, "Anna");
        // A write lands while the build is reading: it adds Bob and invalidates.
        duringList = () -> {
            store(PROJECT, "Bob");
            index.invalidate(TENANT, PROJECT);
        };

        assertThat(names()).containsExactly("Anna");
        // The stale build must not have been stored — the next read sees Bob.
        assertThat(names()).containsExactlyInAnyOrder("Anna", "Bob");
        verify(documentService, times(2)).listUnderFolder(TENANT, PROJECT, ContactIds.FOLDER);
    }

    @Test
    void invalidatingAnUnknownProjectLeavesNothingBehind() {
        index.invalidate(TENANT, "elsewhere");
        assertThat(index.cachedProjects()).isZero();
    }

    @Test
    void expiredSnapshotsAreEvicted() {
        store(PROJECT, "Anna");
        store("other", "Carl");
        names();
        index.entries(TENANT, "other", ContactScope.PROJECT);
        assertThat(index.cachedProjects()).isEqualTo(2);

        clock.advance(ContactIndex.TTL.plusSeconds(1));
        names();

        assertThat(index.cachedProjects()).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-17T10:00:00Z"));

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
