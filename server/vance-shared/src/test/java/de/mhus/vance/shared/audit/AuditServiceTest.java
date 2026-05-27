package de.mhus.vance.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.metric.MetricService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditServiceTest {

    private SimpleMeterRegistry registry;
    private MetricService metricService;
    private AuditService service;
    private CapturingConsumer consumer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricService = new MetricService(registry);
        consumer = new CapturingConsumer();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void record_inSyncMode_dispatchesOnCallerThread() {
        service = buildService(AuditMode.SYNC);

        AuditEventDto event = AuditEventDto.builder()
                .action("auth.login")
                .actor("alice")
                .build();
        service.record(event);

        assertThat(consumer.events).hasSize(1);
        assertThat(consumer.events.get(0).getAction()).isEqualTo("auth.login");
        // dispatch happened on caller thread, not on a worker
        assertThat(consumer.threadNames).containsExactly(Thread.currentThread().getName());
    }

    @Test
    void record_fillsMissingTimestampAndSeverity() {
        service = buildService(AuditMode.SYNC);

        service.record(new AuditEventDto());  // everything null

        AuditEventDto seen = consumer.events.get(0);
        assertThat(seen.getTimestamp()).isNotNull();
        assertThat(seen.getSeverity()).isEqualTo(AuditSeverity.INFO);
    }

    @Test
    void record_inAsyncMode_dispatchesOnWorker() throws Exception {
        service = buildService(AuditMode.ASYNC);

        service.record(AuditEventDto.builder().action("test.async").build());

        waitUntilTrue(() -> !consumer.events.isEmpty(), 2_000);
        assertThat(consumer.events).hasSize(1);
        // worker thread is named "vance-audit-worker"
        assertThat(consumer.threadNames.get(0)).isEqualTo("vance-audit-worker");
    }

    @Test
    void setMode_asyncToSync_drainsQueueBeforeFlipping() throws Exception {
        // Use a blocking consumer so events pile up in the queue while async
        BlockingConsumer blocker = new BlockingConsumer();
        service = new AuditService(propsWith(AuditMode.ASYNC, 100, 5_000),
                metricService, List.of(blocker));
        service.init();

        for (int i = 0; i < 5; i++) {
            service.record(AuditEventDto.builder().action("ev." + i).build());
        }
        // Let the worker pick one up — it'll block on the latch
        Thread.sleep(100);
        // Release the consumer so subsequent dispatches can run
        blocker.gate.countDown();

        service.setMode(AuditMode.SYNC);

        assertThat(service.getMode()).isEqualTo(AuditMode.SYNC);
        assertThat(service.getQueueDepth()).isZero();
        assertThat(blocker.count.get()).isEqualTo(5);
    }

    @Test
    void shutdown_drainsRemainingEvents() throws Exception {
        service = buildService(AuditMode.ASYNC);
        // Pause the worker briefly to ensure events queue up
        SlowConsumer slow = new SlowConsumer(50);
        service.addConsumer(slow);
        service.removeConsumer(consumer);  // isolate

        for (int i = 0; i < 10; i++) {
            service.record(AuditEventDto.builder().action("ev." + i).build());
        }
        service.shutdown();
        service = null;  // already shut down — don't double-shutdown in @AfterEach

        assertThat(slow.count.get()).isEqualTo(10);
    }

    @Test
    void record_dropsEventsWhenQueueFull_andCountsThem() throws Exception {
        BlockingConsumer blocker = new BlockingConsumer();
        // capacity 2 — third event must drop
        service = new AuditService(propsWith(AuditMode.ASYNC, 2, 5_000),
                metricService, List.of(blocker));
        service.init();
        // Drain one slot into the worker, then 2 fill the queue, 2 more get dropped
        // Worker picks first event and blocks on the latch.
        service.record(AuditEventDto.builder().action("ev.0").build());
        Thread.sleep(100);  // give worker time to take one
        service.record(AuditEventDto.builder().action("ev.1").build());
        service.record(AuditEventDto.builder().action("ev.2").build());
        service.record(AuditEventDto.builder().action("ev.3").build());  // dropped
        service.record(AuditEventDto.builder().action("ev.4").build());  // dropped

        double dropped = registry.counter("vance.audit.dropped").count();
        assertThat(dropped).isGreaterThanOrEqualTo(2.0);

        blocker.gate.countDown();
    }

    @Test
    void consumerThrowing_doesNotBreakPipeline() {
        ThrowingConsumer thrower = new ThrowingConsumer();
        service = new AuditService(propsWith(AuditMode.SYNC, 100, 5_000),
                metricService, List.of(thrower, consumer));
        service.init();

        service.record(AuditEventDto.builder().action("ev.1").build());
        service.record(AuditEventDto.builder().action("ev.2").build());

        // capturing consumer still receives both
        assertThat(consumer.events).hasSize(2);
        // exception counter was bumped
        double exCount = registry.find("vance.exceptions").counters().stream()
                .mapToDouble(c -> c.count()).sum();
        assertThat(exCount).isGreaterThanOrEqualTo(2.0);
    }

    @Test
    void addAndRemoveConsumer_takeEffectImmediately() {
        service = buildService(AuditMode.SYNC);

        CapturingConsumer extra = new CapturingConsumer();
        service.addConsumer(extra);
        service.record(AuditEventDto.builder().action("a").build());
        assertThat(extra.events).hasSize(1);

        service.removeConsumer(extra);
        service.record(AuditEventDto.builder().action("b").build());
        assertThat(extra.events).hasSize(1);  // unchanged after removal
    }

    @Test
    void emptyConsumerList_actsAsNoop() {
        service = new AuditService(propsWith(AuditMode.SYNC, 100, 5_000),
                metricService, List.of());
        service.init();
        // Just must not throw
        service.record(AuditEventDto.builder().action("noop").build());
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private AuditService buildService(AuditMode mode) {
        AuditService s = new AuditService(propsWith(mode, 100, 5_000),
                metricService, List.of(consumer));
        s.init();
        return s;
    }

    private AuditServiceProperties propsWith(AuditMode mode, int queueSize, long drain) {
        AuditServiceProperties p = new AuditServiceProperties();
        p.setMode(mode);
        p.setQueueSize(queueSize);
        p.setDrainTimeoutMs(drain);
        return p;
    }

    private static void waitUntilTrue(java.util.function.BooleanSupplier predicate, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (predicate.getAsBoolean()) return;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        throw new AssertionError("predicate not satisfied within " + timeoutMs + "ms");
    }

    private static class CapturingConsumer implements AuditConsumer {
        final List<AuditEventDto> events = new CopyOnWriteArrayList<>();
        final List<String> threadNames = new CopyOnWriteArrayList<>();

        @Override
        public void consume(AuditEventDto event) {
            events.add(event);
            threadNames.add(Thread.currentThread().getName());
        }
    }

    private static class BlockingConsumer implements AuditConsumer {
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicInteger count = new AtomicInteger();

        @Override
        public void consume(AuditEventDto event) {
            try {
                gate.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            count.incrementAndGet();
        }
    }

    private static class SlowConsumer implements AuditConsumer {
        final long delayMs;
        final AtomicInteger count = new AtomicInteger();

        SlowConsumer(long delayMs) { this.delayMs = delayMs; }

        @Override
        public void consume(AuditEventDto event) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            count.incrementAndGet();
        }
    }

    private static class ThrowingConsumer implements AuditConsumer {
        @Override
        public void consume(AuditEventDto event) {
            throw new RuntimeException("boom");
        }
    }
}
