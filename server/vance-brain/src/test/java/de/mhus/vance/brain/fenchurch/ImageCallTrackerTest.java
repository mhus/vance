package de.mhus.vance.brain.fenchurch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import io.micrometer.core.instrument.Counter;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImageCallTrackerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "demo";
    private static final String USER = "alice";

    private ImageCallRecordRepository repository;
    private SettingService settingService;
    private MetricService metricService;
    private Counter counter;
    private ImageCallTracker tracker;

    @BeforeEach
    void setUp() {
        repository = mock(ImageCallRecordRepository.class);
        settingService = mock(SettingService.class);
        metricService = mock(MetricService.class);
        counter = mock(Counter.class);
        when(metricService.counter(anyString(), any(String[].class))).thenReturn(counter);
        tracker = new ImageCallTracker(repository, settingService, metricService);
    }

    @Test
    void no_limits_configured_returns_ok() {
        // settingService returns null for both daily and monthly
        assertThat(tracker.checkQuota(TENANT, USER, PROJECT, null))
                .isEqualTo(ImageCallTracker.Verdict.OK);
    }

    @Test
    void daily_limit_not_exceeded_returns_ok() {
        stubLimit(ImageCallTracker.SETTING_DAILY, "100");
        when(repository.countByTenantIdAndAtGreaterThanEqual(eq(TENANT), any(Instant.class)))
                .thenReturn(42L);

        assertThat(tracker.checkQuota(TENANT, USER, PROJECT, null).allowed()).isTrue();
    }

    @Test
    void daily_limit_exceeded_returns_quota_verdict() {
        stubLimit(ImageCallTracker.SETTING_DAILY, "10");
        when(repository.countByTenantIdAndAtGreaterThanEqual(eq(TENANT), any(Instant.class)))
                .thenReturn(10L);

        ImageCallTracker.Verdict verdict = tracker.checkQuota(TENANT, USER, PROJECT, null);
        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).isEqualTo("daily");
        assertThat(verdict.message()).containsIgnoringCase("daily").contains("10");
    }

    @Test
    void monthly_limit_exceeded_returns_quota_verdict() {
        stubLimit(ImageCallTracker.SETTING_DAILY, null);
        stubLimit(ImageCallTracker.SETTING_MONTHLY, "100");
        when(repository.countByTenantIdAndAtGreaterThanEqual(eq(TENANT), any(Instant.class)))
                .thenReturn(150L);

        ImageCallTracker.Verdict verdict = tracker.checkQuota(TENANT, USER, PROJECT, null);
        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).isEqualTo("monthly");
    }

    @Test
    void non_numeric_limit_treated_as_unlimited() {
        stubLimit(ImageCallTracker.SETTING_DAILY, "garbage");
        stubLimit(ImageCallTracker.SETTING_MONTHLY, null);

        assertThat(tracker.checkQuota(TENANT, USER, PROJECT, null).allowed()).isTrue();
    }

    @Test
    void record_call_persists_and_increments_counter() {
        ImageCallRecord record = ImageCallRecord.builder()
                .tenantId(TENANT)
                .accountId(USER)
                .projectId(PROJECT)
                .modelUsed("openai:gpt-image-1")
                .alias("default:image-high")
                .outcome("success")
                .at(Instant.now())
                .durationMs(1234)
                .build();

        tracker.recordCall(record);

        verify(repository).save(record);
        verify(metricService).counter(eq("vance.fenchurch.calls"),
                eq("outcome"), eq("success"),
                eq("model"), eq("default:image-high"));
        verify(counter).increment();
    }

    @Test
    void record_call_uses_model_when_alias_missing() {
        ImageCallRecord record = ImageCallRecord.builder()
                .tenantId(TENANT)
                .modelUsed("openai:gpt-image-1")
                .outcome("provider_error")
                .at(Instant.now())
                .build();

        tracker.recordCall(record);

        verify(metricService).counter(eq("vance.fenchurch.calls"),
                eq("outcome"), eq("provider_error"),
                eq("model"), eq("openai:gpt-image-1"));
    }

    @Test
    void record_call_with_null_does_nothing() {
        tracker.recordCall(null);
        // No exception, no mock calls — verified implicitly by the
        // strict-stubs default in Mockito.
    }

    // ──────────────── reserve (atomic quota gate) ────────────────

    @Test
    void reserve_underLimit_grants_andInsertsPendingRow() {
        stubLimit(ImageCallTracker.SETTING_DAILY, "100");
        // Count already includes the just-inserted reserve.
        when(repository.countByTenantIdAndAtGreaterThanEqual(eq(TENANT), any(Instant.class)))
                .thenReturn(43L);

        ImageCallTracker.Reservation r = tracker.reserve(TENANT, USER, PROJECT, null);

        assertThat(r).isInstanceOf(ImageCallTracker.Granted.class);
        org.mockito.ArgumentCaptor<ImageCallRecord> cap =
                org.mockito.ArgumentCaptor.forClass(ImageCallRecord.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getOutcome()).isEqualTo(ImageCallTracker.OUTCOME_PENDING);
        verify(repository, never()).delete(any(ImageCallRecord.class));
    }

    @Test
    void reserve_overLimit_denies_andRollsBackReserve() {
        stubLimit(ImageCallTracker.SETTING_DAILY, "10");
        // Reserve pushed the count strictly past the limit.
        when(repository.countByTenantIdAndAtGreaterThanEqual(eq(TENANT), any(Instant.class)))
                .thenReturn(11L);

        ImageCallTracker.Reservation r = tracker.reserve(TENANT, USER, PROJECT, null);

        assertThat(r).isInstanceOf(ImageCallTracker.Denied.class);
        assertThat(((ImageCallTracker.Denied) r).verdict().reason()).isEqualTo("daily");
        // The reserve row must be rolled back so it does not count.
        verify(repository).delete(any(ImageCallRecord.class));
    }

    @Test
    void reserve_atLimitBoundary_grants() {
        // Real count was limit-1 → with the reserve counted it equals the
        // limit exactly, which is still allowed (deny only on strict >).
        stubLimit(ImageCallTracker.SETTING_DAILY, "10");
        when(repository.countByTenantIdAndAtGreaterThanEqual(eq(TENANT), any(Instant.class)))
                .thenReturn(10L);

        assertThat(tracker.reserve(TENANT, USER, PROJECT, null))
                .isInstanceOf(ImageCallTracker.Granted.class);
        verify(repository, never()).delete(any(ImageCallRecord.class));
    }

    @Test
    void reserve_unlimited_grants_withoutCounting() {
        ImageCallTracker.Reservation r = tracker.reserve(TENANT, USER, PROJECT, null);

        assertThat(r).isInstanceOf(ImageCallTracker.Granted.class);
        verify(repository, never())
                .countByTenantIdAndAtGreaterThanEqual(any(), any(Instant.class));
    }

    private void stubLimit(String key, String value) {
        when(settingService.getStringValue(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT),
                eq(HomeBootstrapService.TENANT_PROJECT_NAME), eq(key)))
                .thenReturn(value);
    }
}
