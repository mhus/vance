package de.mhus.vance.brain.ursascheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the path-consistency contract between {@link SchedulerLogService#onTriggered}
 * and {@link SchedulerLogService#pathFor} — the regression that
 * {@code mhus/vance#1} reported as "scheduler_fire returns a logPath
 * but the document never exists at that path".
 *
 * <p>Root cause was a second-precision timestamp in the path plus two
 * independent {@code Instant.now()} calls (one in {@code FireTool}
 * computing the returned path, one inside {@code onTriggered}
 * computing the write path). When they fell in adjacent seconds the
 * paths diverged. The fix forces {@code onTriggered} to use a caller-
 * supplied {@code firedAt}, so the caller can compute the same path
 * the writer uses.
 */
class SchedulerLogServicePathTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "wile";
    private static final String SCHEDULER = "ping-every-20s";
    private static final String CORRELATION_ID = "run_abc-123";
    private static final String RUN_AS = "wile.coyote";

    @Test
    void onTriggered_writesDocumentAtPathDerivedFromCallerFiredAt() {
        DocumentService docs = mock(DocumentService.class);
        SettingService settings = mock(SettingService.class);
        // 7-day default; setting returns null so the default applies.
        when(settings.getStringValueCascade(eq(TENANT), eq(PROJECT), any(), anyString()))
                .thenReturn(null);
        when(docs.upsertEphemeralText(anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), any()))
                .thenReturn(new DocumentDocument());

        SchedulerLogService service = new SchedulerLogService(docs, settings, 7);

        // Deliberately pick an instant right at the edge of a second to
        // make the regression obvious — pre-fix this exact instant
        // wouldn't necessarily be the one used by the writer.
        Instant firedAt = Instant.parse("2026-06-09T18:55:56.999000000Z");

        service.onTriggered(TENANT, PROJECT, SCHEDULER, CORRELATION_ID, "manual", RUN_AS, firedAt);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(docs).upsertEphemeralText(
                eq(TENANT), eq(PROJECT), pathCaptor.capture(),
                any(), any(), anyString(), any(), any());

        String expectedPath = SchedulerLogService.pathFor(SCHEDULER, firedAt, CORRELATION_ID);
        assertThat(pathCaptor.getValue())
                .as("Path written by onTriggered must match pathFor(firedAt) — otherwise the caller-returned logPath points at a non-existent document (mhus/vance#1)")
                .isEqualTo(expectedPath);
    }

    @Test
    void pathFor_secondPrecision_isStableAcrossInstantsInSameSecond() {
        // Two instants in the *same* second produce the same path.
        Instant a = Instant.parse("2026-06-09T18:55:56.001Z");
        Instant b = Instant.parse("2026-06-09T18:55:56.999Z");
        assertThat(SchedulerLogService.pathFor(SCHEDULER, a, CORRELATION_ID))
                .isEqualTo(SchedulerLogService.pathFor(SCHEDULER, b, CORRELATION_ID));
    }

    @Test
    void pathFor_secondPrecision_differsAcrossSecondBoundary() {
        // Demonstrates exactly the race window: 1 ms apart but in
        // adjacent seconds → different paths.
        Instant before = Instant.parse("2026-06-09T18:55:56.999Z");
        Instant after = Instant.parse("2026-06-09T18:55:57.000Z");
        assertThat(SchedulerLogService.pathFor(SCHEDULER, before, CORRELATION_ID))
                .isNotEqualTo(SchedulerLogService.pathFor(SCHEDULER, after, CORRELATION_ID));
    }
}
