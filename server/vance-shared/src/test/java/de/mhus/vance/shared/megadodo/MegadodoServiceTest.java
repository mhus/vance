package de.mhus.vance.shared.megadodo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.megadodo.MegadodoPhase;
import de.mhus.vance.api.megadodo.MegadodoRefType;
import de.mhus.vance.api.megadodo.MegadodoSeverity;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The feed writer. Three things are worth pinning down: retention is
 * tri-state, a broken Mongo must not break the run being observed, and
 * keyset paging must not hand out a cursor when there is no next page.
 */
class MegadodoServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj";

    private MongoTemplate mongoTemplate;
    private SettingService settingService;
    private MegadodoService service;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        settingService = mock(SettingService.class);
        service = new MegadodoService(mongoTemplate, settingService, 90);
    }

    // ──── Emitting ───────────────────────────────────────────────────

    @Test
    void schedulerRunFailed_isAnErrorRowCarryingTheCause() {
        service.schedulerRunFinished(TENANT, PROJECT, "nightly", "run_1",
                /*success*/ false, "cleanup.js line 14: todo is not defined", "_vance/logs/x.md");

        MegadodoEventDocument saved = captureInsert();
        assertThat(saved.getSeverity()).isEqualTo(MegadodoSeverity.ERROR);
        assertThat(saved.getOutcome()).isEqualTo("failure");
        assertThat(saved.getPhase()).isEqualTo(MegadodoPhase.END);
        assertThat(saved.getTraceId()).isEqualTo("run_1");
        assertThat(saved.getRefType()).isEqualTo(MegadodoRefType.SCHEDULER);
        assertThat(saved.getRefId()).isEqualTo("nightly");
        // The reader has to learn what to fix from this line alone.
        assertThat(saved.getMessage()).contains("todo is not defined");
        assertThat(saved.getLogPath()).isEqualTo("_vance/logs/x.md");
    }

    @Test
    void skippedRun_closesTheTraceInsteadOfLeavingItOpen() {
        // A START row is already out; a SINGLE here would read as
        // "still running" forever in the collapsed view.
        service.schedulerRunSkipped(TENANT, PROJECT, "nightly", "run_1", "previous run active");

        MegadodoEventDocument saved = captureInsert();
        assertThat(saved.getPhase()).isEqualTo(MegadodoPhase.END);
        assertThat(saved.getOutcome()).isEqualTo("skipped");
        assertThat(saved.getSeverity()).isEqualTo(MegadodoSeverity.WARN);
    }

    @Test
    void userEvents_areTenantWide() {
        service.userCreated(TENANT, "marvin", /*serviceAccount*/ false);

        assertThat(captureInsert().getProjectId()).isNull();
    }

    @Test
    void longMessage_isTruncatedRatherThanStored() {
        service.schedulerRunFinished(TENANT, PROJECT, "nightly", "run_1",
                false, "x".repeat(5000), null);

        String message = captureInsert().getMessage();
        assertThat(message).hasSize(MegadodoService.MAX_MESSAGE_CHARS);
        assertThat(message).endsWith("…");
    }

    // ──── Retention ──────────────────────────────────────────────────

    @Test
    void retention_defaultsToTheConfiguredWindow() {
        Instant before = Instant.now();

        service.userCreated(TENANT, "marvin", false);

        Instant expiresAt = captureInsert().getExpiresAt();
        assertThat(expiresAt).isNotNull();
        assertThat(expiresAt).isAfter(before.plus(89, ChronoUnit.DAYS));
    }

    @Test
    void retention_zeroMeansForever_soNoTtlAnchorIsWritten() {
        when(settingService.getStringValueCascade(
                eq(TENANT), any(), any(), eq(MegadodoService.SETTING_RETENTION_DAYS)))
                .thenReturn("0");

        service.userCreated(TENANT, "marvin", false);

        assertThat(captureInsert().getExpiresAt()).isNull();
    }

    @Test
    void retention_negativeMeansDoNotWriteAtAll() {
        when(settingService.getStringValueCascade(
                eq(TENANT), any(), any(), eq(MegadodoService.SETTING_RETENTION_DAYS)))
                .thenReturn("-1");

        service.userCreated(TENANT, "marvin", false);

        verify(mongoTemplate, never()).insert(any(MegadodoEventDocument.class));
    }

    @Test
    void retention_unparseableSettingFallsBackToTheDefault() {
        when(settingService.getStringValueCascade(
                eq(TENANT), any(), any(), eq(MegadodoService.SETTING_RETENTION_DAYS)))
                .thenReturn("soon");

        service.userCreated(TENANT, "marvin", false);

        assertThat(captureInsert().getExpiresAt()).isNotNull();
    }

    // ──── Failure isolation ──────────────────────────────────────────

    @Test
    void mongoFailure_isSwallowed_soDiagnosticsNeverBreakTheRun() {
        doThrow(new RuntimeException("mongo down"))
                .when(mongoTemplate).insert(any(MegadodoEventDocument.class));

        // No exception escapes to the scheduler that emitted this.
        service.schedulerRunStarted(TENANT, PROJECT, "nightly", "run_1", "marvin", null);
    }

    // ──── Paging ─────────────────────────────────────────────────────

    @Test
    void query_withoutMoreRows_returnsNoCursor() {
        when(mongoTemplate.find(any(Query.class), eq(MegadodoEventDocument.class)))
                .thenReturn(List.of(row("a"), row("b")));

        MegadodoService.MegadodoPage page =
                service.query(MegadodoQuery.ofProject(TENANT, PROJECT, 10));

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void query_withMoreRows_trimsToLimitAndHandsOutACursor() {
        // The service over-fetches by one to detect the next page without
        // a second count query — the extra row must not reach the caller.
        when(mongoTemplate.find(any(Query.class), eq(MegadodoEventDocument.class)))
                .thenReturn(List.of(row("a"), row("b"), row("c")));

        MegadodoService.MegadodoPage page =
                service.query(MegadodoQuery.ofProject(TENANT, PROJECT, 2));

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNotBlank();
    }

    @Test
    void cursor_roundTrips() {
        Instant at = Instant.parse("2026-08-23T10:00:00Z");
        String encoded = new MegadodoService.Cursor(at, "abc").encode();

        MegadodoService.Cursor decoded = MegadodoService.Cursor.decode(encoded);

        assertThat(decoded).isNotNull();
        assertThat(decoded.timestamp()).isEqualTo(at);
        assertThat(decoded.id()).isEqualTo("abc");
    }

    @Test
    void malformedCursor_startsFromTheTopInsteadOfFailing() {
        // A stale bookmark should show the newest page, not an error.
        assertThat(MegadodoService.Cursor.decode("not-base64!!")).isNull();
    }

    // ──── helpers ────────────────────────────────────────────────────

    private MegadodoEventDocument captureInsert() {
        ArgumentCaptor<MegadodoEventDocument> captor =
                ArgumentCaptor.forClass(MegadodoEventDocument.class);
        verify(mongoTemplate).insert(captor.capture());
        return captor.getValue();
    }

    private static MegadodoEventDocument row(String id) {
        MegadodoEventDocument doc = MegadodoEventDocument.builder()
                .tenantId(TENANT)
                .projectId(PROJECT)
                .timestamp(Instant.parse("2026-08-23T10:00:00Z"))
                .traceId("t")
                .build();
        doc.setId(id);
        return doc;
    }
}
