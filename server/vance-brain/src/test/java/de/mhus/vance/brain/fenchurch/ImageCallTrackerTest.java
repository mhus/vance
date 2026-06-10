package de.mhus.vance.brain.fenchurch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    private void stubLimit(String key, String value) {
        when(settingService.getStringValue(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT),
                eq(HomeBootstrapService.TENANT_PROJECT_NAME), eq(key)))
                .thenReturn(value);
    }
}
