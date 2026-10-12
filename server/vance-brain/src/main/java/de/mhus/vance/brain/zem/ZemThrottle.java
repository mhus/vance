package de.mhus.vance.brain.zem;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Per-instance gate for decision calls on this pod: at most
 * {@code maxConcurrent} calls in flight, at most {@code rps} call starts
 * per second. No provider offers a batch API, so throughput comes from
 * parallel calls — and the two knobs are what keeps that from becoming a
 * 429 storm (hosted) or a queue of timeouts (Ollama serves one call at a
 * time). Both belong to the instance, not the model: they depend on the
 * operator's hardware or contract.
 *
 * <p>Keyed per tenant and instance, because instance credentials — and
 * thus the provider-side limit — are tenant-scoped. No cross-pod
 * coordination (Redis is for live features only); several pods share the
 * provider limit and the service's 429 back-off absorbs the overlap.
 */
@Component
public class ZemThrottle {

    private final ConcurrentMap<String, Gate> gates = new ConcurrentHashMap<>();

    /**
     * Block until a call may start. Close the returned permit when the
     * call is done.
     *
     * @param maxConcurrent {@code null} = unbounded
     * @param rps           {@code null} = no rate limit
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public Permit acquire(String key, @Nullable Integer maxConcurrent, @Nullable Double rps)
            throws InterruptedException {
        Gate gate = gates.compute(
                key,
                (k, existing) -> existing != null && existing.matches(maxConcurrent, rps)
                        ? existing
                        : new Gate(maxConcurrent, rps));
        return gate.enter();
    }

    /** An admitted call; closing it frees the concurrency slot. */
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * One limit set. A changed setting builds a new gate; calls still
     * holding a permit of the old one release into the old semaphore,
     * which is then garbage — no permit ever crosses gates.
     */
    private static final class Gate {

        private final @Nullable Integer maxConcurrent;
        private final @Nullable Double rps;
        private final @Nullable Semaphore slots;
        private final long intervalNanos;
        private long nextStartNanos;

        Gate(@Nullable Integer maxConcurrent, @Nullable Double rps) {
            this.maxConcurrent = maxConcurrent;
            this.rps = rps;
            this.slots = maxConcurrent == null ? null : new Semaphore(maxConcurrent, true);
            this.intervalNanos = rps == null ? 0 : (long) (TimeUnit.SECONDS.toNanos(1) / rps);
            this.nextStartNanos = System.nanoTime();
        }

        boolean matches(@Nullable Integer otherConcurrent, @Nullable Double otherRps) {
            return Objects.equals(maxConcurrent, otherConcurrent) && Objects.equals(rps, otherRps);
        }

        Permit enter() throws InterruptedException {
            if (slots != null) {
                slots.acquire();
            }
            try {
                pace();
            } catch (InterruptedException e) {
                if (slots != null) {
                    slots.release();
                }
                throw e;
            }
            Semaphore held = slots;
            return () -> {
                if (held != null) {
                    held.release();
                }
            };
        }

        /** Reserve the next start slot, then sleep until it arrives. */
        private void pace() throws InterruptedException {
            if (intervalNanos <= 0) {
                return;
            }
            long start;
            synchronized (this) {
                long now = System.nanoTime();
                start = Math.max(now, nextStartNanos);
                nextStartNanos = start + intervalNanos;
            }
            long wait = start - System.nanoTime();
            if (wait > 0) {
                TimeUnit.NANOSECONDS.sleep(wait);
            }
        }
    }
}
