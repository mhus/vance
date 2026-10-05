package de.mhus.vance.brain.hotblack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.hotblack.AudioCallRecord;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import io.micrometer.core.instrument.Counter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AudioCallTrackerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "demo";
    private static final String USER = "alice";

    private AudioCallRecordRepository repository;
    private SettingService settingService;
    private MetricService metricService;
    private Counter counter;
    private AudioCallTracker tracker;

    @BeforeEach
    void setUp() {
        repository = mock(AudioCallRecordRepository.class);
        settingService = mock(SettingService.class);
        metricService = mock(MetricService.class);
        counter = mock(Counter.class);
        when(metricService.counter(anyString(), any(String[].class))).thenReturn(counter);
        tracker = new AudioCallTracker(repository, settingService, metricService);
    }

    @Test
    void no_limits_configured_grants_the_reservation() {
        AudioCallTracker.Reservation reservation = tracker.reserve("tts", TENANT, USER, PROJECT, null);

        assertThat(reservation).isInstanceOf(AudioCallTracker.Granted.class);
    }

    @Test
    void daily_limit_under_the_count_grants_the_reservation() {
        stubLimit(AudioCallTracker.SETTING_DAILY_TTS, "100");
        when(repository.countByTenantIdAndModalityAndAtGreaterThanEqual(eq(TENANT), eq("tts"), any(Instant.class)))
                .thenReturn(42L);

        assertThat(tracker.reserve("tts", TENANT, USER, PROJECT, null)).isInstanceOf(AudioCallTracker.Granted.class);
    }

    @Test
    void daily_limit_reached_denies_and_names_the_modality() {
        stubLimit(AudioCallTracker.SETTING_DAILY_TTS, "10");
        // The reserve row is already in the count (11 with it) — over the
        // limit, so the reservation is rolled back and denied.
        when(repository.countByTenantIdAndModalityAndAtGreaterThanEqual(eq(TENANT), eq("tts"), any(Instant.class)))
                .thenReturn(11L);

        AudioCallTracker.Reservation reservation = tracker.reserve("tts", TENANT, USER, PROJECT, null);

        assertThat(reservation).isInstanceOf(AudioCallTracker.Denied.class);
        AudioCallTracker.Denied denied = (AudioCallTracker.Denied) reservation;
        assertThat(denied.verdict().reason()).isEqualTo("daily");
        assertThat(denied.verdict().message()).contains("tts").contains("10");
    }

    @Test
    void limits_are_per_modality() {
        stubLimit(AudioCallTracker.SETTING_DAILY_MUSIC, "1");
        when(repository.countByTenantIdAndModalityAndAtGreaterThanEqual(eq(TENANT), eq("music"), any(Instant.class)))
                .thenReturn(2L);
        when(repository.countByTenantIdAndModalityAndAtGreaterThanEqual(eq(TENANT), eq("stt"), any(Instant.class)))
                .thenReturn(0L);

        assertThat(tracker.reserve("music", TENANT, USER, PROJECT, null)).isInstanceOf(AudioCallTracker.Denied.class);
        assertThat(tracker.reserve("stt", TENANT, USER, PROJECT, null)).isInstanceOf(AudioCallTracker.Granted.class);
    }

    @Test
    void non_numeric_limit_is_treated_as_unlimited() {
        stubLimit(AudioCallTracker.SETTING_DAILY_STT, "banana");

        assertThat(tracker.reserve("stt", TENANT, USER, PROJECT, null)).isInstanceOf(AudioCallTracker.Granted.class);
    }

    @Test
    void record_call_persists_the_row() {
        AudioCallRecord record = AudioCallRecord.builder()
                .tenantId(TENANT)
                .modality("tts")
                .modelUsed("openrouter:google/gemini-3.8-flash-lite-tts")
                .build();
        tracker.recordCall(record);

        verify(repository).save(record);
        verify(counter).increment();
    }

    @Test
    void sweep_finalizes_abandoned_pending_reserves_as_cancelled() {
        AudioCallRecord stale = AudioCallRecord.builder()
                .tenantId(TENANT)
                .modality("tts")
                .outcome("pending")
                .build();
        when(repository.findByOutcomeAndAtBefore(eq("pending"), any(Instant.class)))
                .thenReturn(List.of(stale));

        tracker.sweepAbandonedReserves();

        assertThat(stale.getOutcome()).isEqualTo(AudioCallTracker.OUTCOME_CANCELLED);
        verify(repository).save(stale);
    }

    @Test
    void sweep_saves_nothing_when_nothing_is_abandoned() {
        when(repository.findByOutcomeAndAtBefore(eq("pending"), any(Instant.class)))
                .thenReturn(List.of());

        tracker.sweepAbandonedReserves();

        verify(repository, never()).save(any(AudioCallRecord.class));
    }

    @Test
    void sweep_never_throws_on_repository_errors() {
        when(repository.findByOutcomeAndAtBefore(anyString(), any(Instant.class)))
                .thenThrow(new RuntimeException("mongo down"));

        assertThatNoException().isThrownBy(() -> tracker.sweepAbandonedReserves());
    }

    private void stubLimit(String key, String value) {
        when(settingService.getStringValue(eq(TENANT), anyString(), anyString(), eq(key)))
                .thenReturn(value);
    }
}
