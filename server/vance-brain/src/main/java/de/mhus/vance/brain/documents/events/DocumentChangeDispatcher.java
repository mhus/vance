package de.mhus.vance.brain.documents.events;

import de.mhus.vance.shared.document.DocumentChangedEvent;
import de.mhus.vance.shared.metric.MetricService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Asynchronous fan-out for remote document-change refreshes. The {@link
 * DocumentChangeRouter} hands every remote-target hit to {@link #enqueue}; a
 * single worker thread drains the bounded queue in batches, coalesces
 * duplicates per target, and POSTs each batch to {@code
 * /internal/document/changed} on the receiving pod.
 *
 * <p>Design choices (see §5 of {@code planning/document-change-events.md}):
 * <ul>
 *   <li><b>Bounded queue + drop-on-overflow.</b> A burst (e.g. kit-install
 *       with dozens of YAMLs) must not back-pressure document writes. When the
 *       queue is full, {@link #enqueue} returns immediately and records a
 *       {@code outcome=dropped} metric. Mongo is the source of truth — a
 *       dropped refresh is fixed by the next bootstrap of the receiving pod.</li>
 *   <li><b>Single worker thread</b> preserves FIFO per target, so an
 *       upsert-then-delete pair on the same path doesn't reorder.</li>
 *   <li><b>Coalescing per batch.</b> 100 events for the same {@code (tenantId,
 *       projectId, path)} compress to one — listeners re-read from Mongo, so
 *       only the latest state matters.</li>
 *   <li><b>POST failures swallow.</b> No retry; the next lazy bootstrap on the
 *       remote pod fixes it.</li>
 *   <li><b>Graceful shutdown.</b> {@link #shutdown} drains in-flight items
 *       with a short timeout; anything left over is dropped (logged).</li>
 * </ul>
 */
@Component
@Slf4j
public class DocumentChangeDispatcher {

    private final HttpDocumentChangedClient httpClient;
    private final MetricService metrics;

    @Value("${vance.document.routing.queue.capacity:10000}")
    private int queueCapacity = 10_000;

    @Value("${vance.document.routing.batch.max:100}")
    private int maxBatchSize = 100;

    @Value("${vance.document.routing.drain.timeout-ms:50}")
    private long drainTimeoutMs = 50;

    @Value("${vance.document.routing.shutdown.timeout-ms:3000}")
    private long shutdownTimeoutMs = 3_000;

    private BlockingQueue<RemoteChange> queue;
    private ExecutorService worker;
    private volatile boolean running = true;

    public DocumentChangeDispatcher(HttpDocumentChangedClient httpClient, MetricService metrics) {
        this.httpClient = httpClient;
        this.metrics = metrics;
    }

    @PostConstruct
    void start() {
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        AtomicReference<Double> queueDepth = metrics.gauge(
                "vance.document.routing.queue.size",
                () -> (double) queue.size());
        // Touch once so the gauge is registered even before any traffic
        queueDepth.set(0.0);
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "document-change-dispatcher");
            t.setDaemon(true);
            return t;
        });
        worker.submit(this::workerLoop);
        log.info("DocumentChangeDispatcher started — capacity={} maxBatch={} drainTimeoutMs={}",
                queueCapacity, maxBatchSize, drainTimeoutMs);
    }

    @PreDestroy
    void shutdown() {
        running = false;
        if (worker == null) return;
        // The worker blocks on queue.take(); plain shutdown() will not
        // unblock it. shutdownNow() interrupts the take, the workerLoop
        // unwinds on InterruptedException and exits. Pending items in the
        // queue are dropped on purpose — Mongo is the source of truth,
        // and a stale cache fixes itself on the next lazy bootstrap.
        int leftover = queue.size();
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("DocumentChangeDispatcher: worker did not stop within {}ms — {} item(s) dropped",
                        shutdownTimeoutMs, leftover);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Enqueue a remote refresh. Returns immediately; the worker drains
     * asynchronously. When the queue is full the change is dropped and a
     * warn-log + metric bump are emitted.
     */
    public void enqueue(String targetEndpoint, DocumentChangedEvent event) {
        if (queue == null) {
            // Pre-PostConstruct call — shouldn't happen in production but
            // protects unit tests that wire the bean without starting it.
            log.warn("DocumentChangeDispatcher: enqueue before start — dropping refresh for '{}/{}/{}'",
                    event.tenantId(), event.projectId(), event.path());
            return;
        }
        RemoteChange change = new RemoteChange(targetEndpoint, event);
        boolean accepted = queue.offer(change);
        if (!accepted) {
            metrics.counter("vance.document.routing.dispatched", "outcome", "dropped").increment();
            log.warn("DocumentChangeDispatcher: queue full (cap={}) — dropping refresh for '{}/{}/{}' → {}",
                    queueCapacity, event.tenantId(), event.projectId(), event.path(), targetEndpoint);
        }
    }

    // Visible for tests so they can trigger a flush without sleeping.
    void drainAndDispatchOnce() throws InterruptedException {
        RemoteChange head = queue.poll(drainTimeoutMs, TimeUnit.MILLISECONDS);
        if (head == null) return;
        List<RemoteChange> batch = new ArrayList<>();
        batch.add(head);
        queue.drainTo(batch, maxBatchSize - 1);
        dispatchBatch(batch);
    }

    private void workerLoop() {
        while (running) {
            try {
                RemoteChange head = queue.take();
                List<RemoteChange> batch = new ArrayList<>();
                batch.add(head);
                // Wait briefly for more items to coalesce. Keeps batches
                // fat under sustained load without burning a turn on each
                // single event.
                Thread.sleep(Math.max(0, drainTimeoutMs));
                queue.drainTo(batch, maxBatchSize - 1);
                dispatchBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                // A worker exception must not kill the thread — log and
                // continue. Otherwise a single broken endpoint would
                // silently stop all routing.
                log.warn("DocumentChangeDispatcher: worker iteration failed: {}", ex.toString(), ex);
            }
        }
    }

    private void dispatchBatch(List<RemoteChange> batch) {
        // Group by target endpoint, coalesce by (tenantId, projectId, path) — latest op wins.
        Map<String, LinkedHashMap<String, DocumentChangedEvent>> byEndpoint = new LinkedHashMap<>();
        int coalesced = 0;
        for (RemoteChange c : batch) {
            LinkedHashMap<String, DocumentChangedEvent> bucket =
                    byEndpoint.computeIfAbsent(c.endpoint, k -> new LinkedHashMap<>());
            String key = c.event.tenantId() + "|" + c.event.projectId() + "|" + c.event.path();
            DocumentChangedEvent prior = bucket.put(key, c.event);
            if (prior != null) coalesced++;
        }
        if (coalesced > 0) {
            metrics.counter("vance.document.routing.coalesced").increment(coalesced);
        }

        for (Map.Entry<String, LinkedHashMap<String, DocumentChangedEvent>> e : byEndpoint.entrySet()) {
            String endpoint = e.getKey();
            List<DocumentChangedEvent> events = new ArrayList<>(e.getValue().values());
            metrics.summary("vance.document.routing.batch.size").record(events.size());
            try {
                httpClient.postBatch(endpoint, events);
                metrics.counter("vance.document.routing.dispatched", "outcome", "success").increment();
            } catch (RuntimeException ex) {
                metrics.counter("vance.document.routing.dispatched", "outcome", "failed").increment();
                log.warn("DocumentChangeDispatcher: POST to '{}' failed ({} events): {}",
                        endpoint, events.size(), ex.toString());
            }
        }
    }

    /** Visible for tests. */
    int queueSize() {
        return queue == null ? 0 : queue.size();
    }

    /** Carrier in the queue — target endpoint + the underlying change. */
    record RemoteChange(String endpoint, DocumentChangedEvent event) {}

    // Visible-for-test config setters. Production paths take the @Value
    // injection; tests pin deterministic values.
    void setQueueCapacityForTest(int cap) { this.queueCapacity = cap; }
    void setDrainTimeoutForTest(Duration d) { this.drainTimeoutMs = d.toMillis(); }
    void setMaxBatchSizeForTest(int max) { this.maxBatchSize = max; }
}
