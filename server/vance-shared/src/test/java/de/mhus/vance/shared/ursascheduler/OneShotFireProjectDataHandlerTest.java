package de.mhus.vance.shared.ursascheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The one-shot marker has no project field — the project is inside its
 * {@code _id}. That makes the id grammar load-bearing for maintenance, and both
 * id shapes have to be handled: the pre-{@code 2026-08-24} {@code /}-joined
 * form is still read by the scheduler, so a project delete that missed it would
 * leave a marker that stops a re-created project's {@code at:} scheduler from
 * ever firing.
 */
class OneShotFireProjectDataHandlerTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final OneShotFireProjectDataHandler handler =
            new OneShotFireProjectDataHandler(mongoTemplate);

    @Test
    void markerId_separatesWithNul_soPartsWithSlashesCannotCollide() {
        // ("a", "b/c", "d") and ("a", "b", "c/d") are different one-shots and
        // must not share a marker — a collision means one silently never fires.
        assertThat(OneShotFireDocument.markerId("a", "b/c", "d"))
                .isNotEqualTo(OneShotFireDocument.markerId("a", "b", "c/d"));
    }

    @Test
    void idPrefix_isTheMarkerIdWithoutTheSchedulerName() {
        assertThat(OneShotFireDocument.markerId("acme", "p1", "nightly"))
                .startsWith(OneShotFireDocument.idPrefix("acme", "p1"));
    }

    @Test
    void rename_reKeysMarkersUnderTheNewProject_keepingTheSchedulerName() {
        OneShotFireDocument marker = OneShotFireDocument.builder()
                .id(OneShotFireDocument.markerId("acme", "p1", "nightly"))
                .scheduledFor(Instant.parse("2026-08-01T00:00:00Z"))
                .firedAt(Instant.parse("2026-08-01T00:00:01Z"))
                .build();
        when(mongoTemplate.find(any(), any())).thenReturn(List.of(marker));

        long moved = handler.rename("acme", "p1", "p2");

        ArgumentCaptor<OneShotFireDocument> saved =
                ArgumentCaptor.forClass(OneShotFireDocument.class);
        verify(mongoTemplate).save(saved.capture());
        assertThat(saved.getValue().getId())
                .isEqualTo(OneShotFireDocument.markerId("acme", "p2", "nightly"));
        assertThat(saved.getValue().getScheduledFor())
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        verify(mongoTemplate).remove(marker);
        assertThat(moved).isEqualTo(1);
    }

    @Test
    void rename_alsoCarriesTheLegacySlashJoinedIds() {
        OneShotFireDocument legacy = OneShotFireDocument.builder()
                .id(OneShotFireDocument.legacyMarkerId("acme", "p1", "nightly"))
                .scheduledFor(Instant.parse("2026-08-01T00:00:00Z"))
                .build();
        when(mongoTemplate.find(any(), any())).thenReturn(List.of(legacy));

        handler.rename("acme", "p1", "p2");

        ArgumentCaptor<OneShotFireDocument> saved =
                ArgumentCaptor.forClass(OneShotFireDocument.class);
        verify(mongoTemplate).save(saved.capture());
        // Re-keyed into the current grammar, not the legacy one it came from.
        assertThat(saved.getValue().getId())
                .isEqualTo(OneShotFireDocument.markerId("acme", "p2", "nightly"));
    }

    @Test
    void rename_skipsAMarkerWhoseIdMatchesNeitherShape() {
        OneShotFireDocument foreign = OneShotFireDocument.builder().id("something-else").build();
        when(mongoTemplate.find(any(), any())).thenReturn(List.of(foreign));

        assertThat(handler.rename("acme", "p1", "p2")).isZero();
        verify(mongoTemplate, org.mockito.Mockito.never()).remove(foreign);
    }
}
