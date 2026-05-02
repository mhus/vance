package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessListResponse;
import de.mhus.vance.api.thinkprocess.ProcessSummary;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the lazy/sticky cache contract: reads return whatever's
 * cached immediately, async-refresh kicks off only when stale, and
 * {@code remember*} fills the cache without a network round-trip.
 */
class SuggestionCacheTest {

    private ConnectionService connection;
    private SuggestionCache cache;

    @BeforeEach
    void setUp() {
        connection = mock(ConnectionService.class);
        when(connection.isOpen()).thenReturn(true);
        cache = new SuggestionCache(connection);
    }

    @Test
    void rememberX_fillsCache_withoutNetworkCall() throws Exception {
        cache.rememberProcesses(List.of("p1", "p2"));

        // Read returns the remembered values immediately.
        assertThat(cache.processes()).containsExactly("p1", "p2");
        // No request was sent — `remember` is the opportunistic-refill path.
        verify(connection, never()).request(any(), any(), any(), any());
    }

    @Test
    void firstRead_whenEmpty_returnsEmpty_andKicksOffAsyncFetch() throws Exception {
        // An empty cache returns immediately with [], not blocking.
        // The fetch fires asynchronously and lands the values for the
        // next Tab.
        ProcessListResponse response = ProcessListResponse.builder()
                .processes(List.of(
                        ProcessSummary.builder().name("from-fetch").build()))
                .build();
        when(connection.request(any(), any(), any(), any())).thenReturn(response);

        // First read: empty (the JLine thread isn't blocked by network).
        assertThat(cache.processes()).isEmpty();

        // Subsequent read: the async fetch has populated the bucket.
        Awaitility.await().atMost(2, TimeUnit.SECONDS)
                .until(() -> !cache.processes().isEmpty());
        assertThat(cache.processes()).containsExactly("from-fetch");
    }

    @Test
    void invalidateAll_dropsEverything() {
        cache.rememberProcesses(List.of("p"));
        cache.rememberSessions(List.of("s"));
        cache.rememberProjects(List.of("pr"));
        cache.rememberProjectGroups(List.of("g"));

        cache.invalidateAll();

        // After invalidation, all buckets are back to empty;
        // the next read will trigger a fetch, but synchronously
        // returns empty.
        assertThat(cache.processes()).isEmpty();
        assertThat(cache.sessions()).isEmpty();
        assertThat(cache.projects()).isEmpty();
        assertThat(cache.projectGroups()).isEmpty();
    }

    @Test
    void rememberAndRead_isIndependentPerBucket() {
        cache.rememberProcesses(List.of("p1"));
        cache.rememberSessions(List.of("s1"));
        cache.rememberProjects(List.of("proj1"));
        cache.rememberProjectGroups(List.of("g1"));

        assertThat(cache.processes()).containsExactly("p1");
        assertThat(cache.sessions()).containsExactly("s1");
        assertThat(cache.projects()).containsExactly("proj1");
        assertThat(cache.projectGroups()).containsExactly("g1");
    }

    @Test
    void closedConnection_yieldsEmpty_andDoesNotRequest() throws Exception {
        when(connection.isOpen()).thenReturn(false);

        // First read: empty + tries to fetch (which short-circuits on closed).
        cache.processes();
        // Wait briefly for the async fetcher to run and exit.
        Thread.sleep(100);

        verify(connection, never()).request(any(), any(), any(), any());
    }

    @Test
    void rememberProcesses_acceptsEmptyList_andOverwritesPriorValues() {
        cache.rememberProcesses(List.of("a", "b"));
        cache.rememberProcesses(List.of());

        // After being remembered as empty, read still returns empty —
        // the cache distinguishes "fresh empty" from "stale" via the
        // bucket's internal timestamp.
        assertThat(cache.processes()).isEmpty();
    }

    @Test
    void rememberProcesses_isDefensiveCopy_notLiveReference() {
        // Cache must not retain the caller's mutable list.
        java.util.List<String> live = new java.util.ArrayList<>();
        live.add("a");
        cache.rememberProcesses(live);
        live.add("b"); // mutate the source after caching

        assertThat(cache.processes()).containsExactly("a");
    }

    @SuppressWarnings("unused")
    private static void quiet(AtomicReference<Duration> sink, Duration d) {
        sink.set(d); // silence unused-import warnings if helpers shift
    }
}
