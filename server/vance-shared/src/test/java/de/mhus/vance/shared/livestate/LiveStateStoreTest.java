package de.mhus.vance.shared.livestate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.projects.ProjectLiveState;
import de.mhus.vance.api.projects.RunnerPhase;
import de.mhus.vance.api.projects.RunnerStatusDto;
import de.mhus.vance.shared.livestate.LiveStateStore.ProjectEntry;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Missing, expired or unreadable entries are "unknown" — never an error and never an old value. */
class LiveStateStoreTest {

    private VanceRedisMessagingService redis;
    private LiveStateStore store;

    @BeforeEach
    void setUp() {
        redis = mock(VanceRedisMessagingService.class);
        store = new LiveStateStore(redis);
    }

    @Test
    void aProjectWithoutAnEntryHasNoLiveState() {
        when(redis.hashGetAll("acme", "live:project:p1")).thenReturn(Map.of());

        assertThat(store.getProject("acme", "p1")).isEmpty();
    }

    @Test
    void aWrittenProjectEntryReadsBackWithItsState() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);

        ProjectEntry written = store.putProject("acme", "p1", ProjectLiveState.RUNNING, Duration.ofMinutes(5));

        verify(redis).hashPut(eq("acme"), eq("live:project:p1"), eq("v"), body.capture(), eq(Duration.ofMinutes(5)));
        when(redis.hashGetAll("acme", "live:project:p1")).thenReturn(Map.of("v", body.getValue()));
        ProjectEntry read = store.getProject("acme", "p1").orElseThrow();
        assertThat(read.state()).isEqualTo(ProjectLiveState.RUNNING);
        assertThat(read.expiresAtMs()).isEqualTo(written.expiresAtMs());
    }

    @Test
    void anExpiredProjectEntryCountsAsUnknown() {
        long past = Instant.now().minusSeconds(10).toEpochMilli();
        when(redis.hashGetAll("acme", "live:project:p1"))
                .thenReturn(Map.of("v", "{\"state\":\"RUNNING\",\"observedAtMs\":1,\"expiresAtMs\":" + past + "}"));

        assertThat(store.getProject("acme", "p1")).isEmpty();
    }

    @Test
    void anUnreadableEntryCountsAsUnknown() {
        when(redis.hashGetAll("acme", "live:project:p1")).thenReturn(Map.of("v", "not json"));

        assertThat(store.getProject("acme", "p1")).isEmpty();
    }

    @Test
    void renewingKeepsTheStateAndMovesTheExpiry() {
        long soon = Instant.now().plusSeconds(30).toEpochMilli();
        when(redis.hashGetAll("acme", "live:project:p1"))
                .thenReturn(Map.of("v", "{\"state\":\"STARTING\",\"observedAtMs\":1,\"expiresAtMs\":" + soon + "}"));

        ProjectEntry renewed =
                store.renewProject("acme", "p1", Duration.ofMinutes(5)).orElseThrow();

        assertThat(renewed.state()).isEqualTo(ProjectLiveState.STARTING);
        assertThat(renewed.expiresAtMs()).isGreaterThan(soon);
    }

    @Test
    void renewingAnEntryThatIsGoneDoesNothing() {
        when(redis.hashGetAll("acme", "live:project:p1")).thenReturn(Map.of());

        assertThat(store.renewProject("acme", "p1", Duration.ofMinutes(5))).isEmpty();
        verify(redis, org.mockito.Mockito.never()).hashPut(any(), any(), any(), any(), any());
    }

    @Test
    void aRunnerEntryIsStoredAndAnnouncedOnTheRunnerChannel() {
        ArgumentCaptor<String> published = ArgumentCaptor.forClass(String.class);

        store.putRunner(
                "acme",
                "runner-acme",
                RunnerPhase.READY,
                2,
                Instant.parse("2026-11-01T10:00:00Z"),
                Duration.ofSeconds(90));

        verify(redis).hashPut(eq("acme"), eq("live:runner:runner-acme"), eq("v"), any(), eq(Duration.ofSeconds(90)));
        verify(redis).publish(eq("acme"), eq(LiveStateStore.RUNNER_CHANNEL), published.capture());
        RunnerStatusDto decoded = store.decodeRunner(published.getValue()).orElseThrow();
        assertThat(decoded.getRunnerId()).isEqualTo("runner-acme");
        assertThat(decoded.getPhase()).isEqualTo(RunnerPhase.READY);
        assertThat(decoded.getRestarts()).isEqualTo(2);
    }

    @Test
    void aRunnerEntryThatRanOutIsUnknown() {
        long past = Instant.now().minusSeconds(1).toEpochMilli();
        when(redis.hashGetAll("acme", "live:runner:r1"))
                .thenReturn(Map.of("v", "{\"runnerId\":\"r1\",\"phase\":\"READY\",\"expiresAtMs\":" + past + "}"));

        assertThat(store.getRunner("acme", "r1")).isEmpty();
    }

    @Test
    void aBrokenRedisNeverFailsTheWriter() {
        org.mockito.Mockito.doThrow(new IllegalStateException("down"))
                .when(redis)
                .hashPut(any(), any(), any(), any(), any());

        assertThat(store.putProject("acme", "p1", ProjectLiveState.RUNNING, Duration.ofMinutes(1)))
                .isNotNull();
    }
}
