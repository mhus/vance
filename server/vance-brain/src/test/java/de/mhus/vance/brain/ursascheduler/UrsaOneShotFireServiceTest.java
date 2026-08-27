package de.mhus.vance.brain.ursascheduler;

import de.mhus.vance.shared.ursascheduler.OneShotFireDocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The one-shot fire marker — the state that used to live as a
 * {@code STARTED} row in {@code event_log}. See {@code planning/megadodo.md}.
 */
@ExtendWith(MockitoExtension.class)
class UrsaOneShotFireServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj";
    private static final String SCHEDULER = "cleanup-once";
    private static final Instant AT = Instant.parse("2026-08-23T10:00:00Z");

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private UrsaOneShotFireService service;

    @Test
    void hasFired_withoutMarker_isFalse() {
        when(mongoTemplate.findById(
                UrsaOneShotFireService.markerId(TENANT, PROJECT, SCHEDULER),
                OneShotFireDocument.class))
                .thenReturn(null);

        assertThat(service.hasFired(TENANT, PROJECT, SCHEDULER, AT)).isFalse();
    }

    @Test
    void hasFired_withMarkerForSameAt_isTrue() {
        when(mongoTemplate.findById(
                UrsaOneShotFireService.markerId(TENANT, PROJECT, SCHEDULER),
                OneShotFireDocument.class))
                .thenReturn(OneShotFireDocument.builder()
                        .id(UrsaOneShotFireService.markerId(TENANT, PROJECT, SCHEDULER))
                        .scheduledFor(AT)
                        .firedAt(AT)
                        .build());

        assertThat(service.hasFired(TENANT, PROJECT, SCHEDULER, AT)).isTrue();
    }

    @Test
    void hasFired_withMarkerForDifferentAt_isFalse() {
        // Document re-created under the same name with a new `at:` — the
        // stale marker must not trash the fresh scheduler on sight.
        when(mongoTemplate.findById(
                UrsaOneShotFireService.markerId(TENANT, PROJECT, SCHEDULER),
                OneShotFireDocument.class))
                .thenReturn(OneShotFireDocument.builder()
                        .id(UrsaOneShotFireService.markerId(TENANT, PROJECT, SCHEDULER))
                        .scheduledFor(AT.minusSeconds(86_400))
                        .firedAt(AT.minusSeconds(86_400))
                        .build());

        assertThat(service.hasFired(TENANT, PROJECT, SCHEDULER, AT)).isFalse();
    }

    @Test
    void markFired_savesMarkerWithConsumedAtAndRun() {
        service.markFired(TENANT, PROJECT, SCHEDULER, AT, "run_42");

        ArgumentCaptor<OneShotFireDocument> saved =
                ArgumentCaptor.forClass(OneShotFireDocument.class);
        verify(mongoTemplate).save(saved.capture());
        assertThat(saved.getValue().getId())
                .isEqualTo(UrsaOneShotFireService.markerId(TENANT, PROJECT, SCHEDULER));
        assertThat(saved.getValue().getScheduledFor()).isEqualTo(AT);
        assertThat(saved.getValue().getCorrelationId()).isEqualTo("run_42");
        assertThat(saved.getValue().getFiredAt()).isNotNull();
    }

    @Test
    void markerId_separatorCannotOccurInsideAPart() {
        // A '/' separator collided: ("acme", "a/b", "c") and ("acme", "a",
        // "b/c") produced the same id, and a collision here is a one-shot that
        // silently never fires.
        assertThat(UrsaOneShotFireService.markerId(TENANT, "a/b", "c"))
                .isNotEqualTo(UrsaOneShotFireService.markerId(TENANT, "a", "b/c"));
    }

    @Test
    void markerId_isScopedToProjectNotJustTenant() {
        // The event_log predecessor keyed on (tenant, "ursascheduler:<name>")
        // only — same-named one-shots in two projects shadowed each other.
        assertThat(UrsaOneShotFireService.markerId(TENANT, "a", SCHEDULER))
                .isNotEqualTo(UrsaOneShotFireService.markerId(TENANT, "b", SCHEDULER));
    }

    @Test
    void markFired_isUpsert_soReArmingOverwritesInsteadOfDuplicating() {
        service.markFired(TENANT, PROJECT, SCHEDULER, AT, "run_1");
        service.markFired(TENANT, PROJECT, SCHEDULER, AT.plusSeconds(3600), "run_2");

        ArgumentCaptor<OneShotFireDocument> saved =
                ArgumentCaptor.forClass(OneShotFireDocument.class);
        verify(mongoTemplate, times(2)).save(saved.capture());
        // Same _id both times — Mongo replaces rather than appends.
        assertThat(saved.getAllValues()).extracting(OneShotFireDocument::getId)
                .containsExactly(
                        UrsaOneShotFireService.markerId(TENANT, PROJECT, SCHEDULER),
                        UrsaOneShotFireService.markerId(TENANT, PROJECT, SCHEDULER));
        assertThat(saved.getAllValues().get(1).getScheduledFor())
                .isEqualTo(AT.plusSeconds(3600));
    }
}
