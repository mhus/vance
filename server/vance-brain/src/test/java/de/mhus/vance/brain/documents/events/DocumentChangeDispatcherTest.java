package de.mhus.vance.brain.documents.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentChangedEvent;
import de.mhus.vance.shared.metric.MetricService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class DocumentChangeDispatcherTest {

    private HttpDocumentChangedClient httpClient;
    private MetricService metrics;
    private Counter counter;
    private DistributionSummary summary;
    private DocumentChangeDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpDocumentChangedClient.class);
        metrics = mock(MetricService.class);
        counter = mock(Counter.class);
        summary = mock(DistributionSummary.class);
        when(metrics.counter(any(), any(String[].class))).thenReturn(counter);
        when(metrics.counter(any())).thenReturn(counter);
        when(metrics.summary(any(), any(String[].class))).thenReturn(summary);
        when(metrics.summary(any())).thenReturn(summary);
        when(metrics.gauge(any(String.class), any(Supplier.class), any(String[].class)))
                .thenReturn(new AtomicReference<>(0.0));
        when(metrics.gauge(any(String.class), any(String[].class), any(Supplier.class)))
                .thenReturn(new AtomicReference<>(0.0));

        dispatcher = new DocumentChangeDispatcher(httpClient, metrics);
        // Production paths use @Value; tests pin deterministic small values
        // so drains are quick and the queue boundary is easy to hit.
        ReflectionTestUtils.setField(dispatcher, "queueCapacity", 4);
        ReflectionTestUtils.setField(dispatcher, "maxBatchSize", 10);
        ReflectionTestUtils.setField(dispatcher, "drainTimeoutMs", 10L);
        ReflectionTestUtils.setField(dispatcher, "shutdownTimeoutMs", 200L);
        dispatcher.start();
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    @Test
    void single_event_yields_one_post_with_one_event() throws Exception {
        DocumentChangedEvent ev = upserted("server-tools/a.yaml", "1");
        dispatcher.enqueue("10.0.0.8:8080", ev);

        Thread.sleep(200);

        ArgumentCaptor<List<DocumentChangedEvent>> cap = listCaptor();
        verify(httpClient, times(1)).postBatch(eq("10.0.0.8:8080"), cap.capture());
        assertThat(cap.getValue()).hasSize(1);
    }

    @Test
    void duplicate_events_for_same_path_coalesce() throws Exception {
        DocumentChangedEvent a = upserted("server-tools/a.yaml", "1");
        DocumentChangedEvent b = upserted("server-tools/a.yaml", "1");
        DocumentChangedEvent c = upserted("server-tools/b.yaml", "2");

        dispatcher.enqueue("ep1", a);
        dispatcher.enqueue("ep1", b);
        dispatcher.enqueue("ep1", c);

        Thread.sleep(200);

        ArgumentCaptor<List<DocumentChangedEvent>> cap = listCaptor();
        verify(httpClient, times(1)).postBatch(eq("ep1"), cap.capture());
        // Two distinct paths → coalesced from 3 inputs to 2.
        assertThat(cap.getValue()).hasSize(2);
    }

    @Test
    void multiple_endpoints_split_into_separate_posts() throws Exception {
        dispatcher.enqueue("ep-a", upserted("p1", "1"));
        dispatcher.enqueue("ep-b", upserted("p1", "1"));

        Thread.sleep(200);

        verify(httpClient, times(1)).postBatch(eq("ep-a"), any());
        verify(httpClient, times(1)).postBatch(eq("ep-b"), any());
    }

    @Test
    void enqueue_on_full_queue_drops_silently_with_metric() {
        // Block the worker so enqueues pile up: keep the http client
        // hanging via a wait inside postBatch.
        Object hold = new Object();
        org.mockito.Mockito.doAnswer(inv -> {
            synchronized (hold) {
                hold.wait(5_000);
            }
            return null;
        }).when(httpClient).postBatch(any(), any());

        // First event triggers the worker to dequeue and block in postBatch.
        dispatcher.enqueue("ep", upserted("p1", "1"));
        // Give the worker a moment to take + block.
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Now fill the queue (capacity 4) plus a few extras.
        for (int i = 0; i < 4; i++) {
            dispatcher.enqueue("ep", upserted("p-extra-" + i, "id-" + i));
        }
        // These should drop:
        dispatcher.enqueue("ep", upserted("p-overflow-1", "x1"));
        dispatcher.enqueue("ep", upserted("p-overflow-2", "x2"));

        // At least one "dropped" counter increment must have happened.
        verify(metrics, org.mockito.Mockito.atLeastOnce())
                .counter(eq("vance.document.routing.dispatched"), eq("outcome"), eq("dropped"));

        // Unblock the worker so shutdown doesn't hang.
        synchronized (hold) { hold.notifyAll(); }
    }

    @Test
    void http_failure_records_failed_outcome_and_does_not_kill_worker() throws Exception {
        doThrow(new RuntimeException("boom"))
                .when(httpClient).postBatch(eq("bad"), any());

        dispatcher.enqueue("bad", upserted("p1", "1"));
        Thread.sleep(150);
        dispatcher.enqueue("bad", upserted("p2", "2"));
        Thread.sleep(150);

        // Worker survived both POSTs — two attempts, two failed metrics.
        verify(httpClient, times(2)).postBatch(eq("bad"), any());
        verify(metrics, times(2))
                .counter(eq("vance.document.routing.dispatched"), eq("outcome"), eq("failed"));
    }

    // ─── helpers ──────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<DocumentChangedEvent>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }

    private static DocumentChangedEvent upserted(String path, String id) {
        return new DocumentChangedEvent.Upserted("acme", "_tenant", path, id);
    }
}
