package de.mhus.vance.brain.zem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ZemThrottleTest {

    @Test
    void concurrency_limit_one_serialises_calls() throws Exception {
        ZemThrottle throttle = new ZemThrottle();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(8);
        try {
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> {
                    try (ZemThrottle.Permit ignored = throttle.acquire("t/ollama", 1, null)) {
                        maxSeen.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        Thread.sleep(5);
                        inFlight.decrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                    return null;
                });
            }
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(maxSeen.get()).isEqualTo(1);
    }

    @Test
    void rate_limit_spaces_call_starts() throws Exception {
        ZemThrottle throttle = new ZemThrottle();
        List<Long> starts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            try (ZemThrottle.Permit ignored = throttle.acquire("t/openrouter", null, 50.0)) {
                starts.add(System.nanoTime());
            }
        }
        long spanMillis = TimeUnit.NANOSECONDS.toMillis(starts.get(3) - starts.get(0));
        assertThat(spanMillis).isGreaterThanOrEqualTo(55); // 3 intervals × 20 ms, minus scheduling slack
    }

    @Test
    void unlimited_gate_does_not_block_and_changed_limits_take_effect() throws Exception {
        ZemThrottle throttle = new ZemThrottle();
        try (ZemThrottle.Permit a = throttle.acquire("k", null, null);
                ZemThrottle.Permit b = throttle.acquire("k", null, null)) {
            assertThat(a).isNotNull();
            assertThat(b).isNotNull();
        }
        // A permit held under limit 1 must not block a gate rebuilt with limit 2.
        try (ZemThrottle.Permit held = throttle.acquire("k", 1, null);
                ZemThrottle.Permit next = throttle.acquire("k", 2, null)) {
            assertThat(next).isNotNull();
        }
    }
}
