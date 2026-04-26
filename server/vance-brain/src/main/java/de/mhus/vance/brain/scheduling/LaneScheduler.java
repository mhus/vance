package de.mhus.vance.brain.scheduling;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Per-lane FIFO executor for engine work.
 *
 * <p>Each {@code laneId} (typically a {@code ThinkProcessDocument.id})
 * gets a logical queue: tasks for that id execute one after another
 * on a virtual thread. Different lanes run in parallel, bounded only
 * by the shared virtual-thread pool.
 *
 * <p>Why not a plain executor? Two reasons:
 * <ul>
 *   <li><b>State safety</b> — a think-process owns mutable state
 *       (chat log, lc4j tool loop, scratchpad). Two concurrent
 *       {@code steer} calls on the same process would race; serial
 *       per lane prevents that without a coarse global lock.</li>
 *   <li><b>WS-frame-thread liberation</b> — handlers submit work and
 *       return immediately, freeing the receive thread to deliver
 *       inbound frames the engine itself may be awaiting (e.g.
 *       {@code client-tool-result}).</li>
 * </ul>
 *
 * <p>Failure of one task does not poison its lane: the chain is
 * stitched with {@code exceptionally(...)} so the next task runs
 * regardless. The original failure still propagates back to the
 * submitting caller via the returned future.
 */
@Service
@Slf4j
public class LaneScheduler {

    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /** Submits a {@link Callable} task; await via the returned future. */
    public <T> CompletableFuture<T> submit(String laneId, Callable<T> task) {
        Lane lane = lanes.computeIfAbsent(
                laneId == null ? "<no-lane>" : laneId, Lane::new);
        return lane.enqueue(task, workers);
    }

    /** Convenience overload for {@link Runnable}-shaped work. */
    public CompletableFuture<Void> submit(String laneId, Runnable task) {
        return submit(laneId, () -> {
            task.run();
            return null;
        });
    }

    /** Number of lanes currently tracked (lifetime — not GC'd on idle yet). */
    public int laneCount() {
        return lanes.size();
    }

    /** Pending + in-flight work for one lane, or {@code 0} if unknown. */
    public int queueDepth(String laneId) {
        Lane l = lanes.get(laneId);
        return l == null ? 0 : l.queueDepth();
    }

    /**
     * Drops the lane bookkeeping for {@code laneId}. Future submits
     * with the same id will start a fresh lane. Safe even if tasks
     * are still in flight on the old lane — they finish on the
     * detached {@link Lane} reference.
     */
    public void forget(String laneId) {
        if (laneId != null) {
            lanes.remove(laneId);
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    /** A single FIFO chain. */
    private static final class Lane {
        private final String id;
        private final Object monitor = new Object();
        private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
        private final AtomicInteger depth = new AtomicInteger();

        Lane(String id) {
            this.id = id;
        }

        <T> CompletableFuture<T> enqueue(Callable<T> task, ExecutorService executor) {
            CompletableFuture<T> result;
            synchronized (monitor) {
                depth.incrementAndGet();
                CompletableFuture<?> previous = tail;
                result = previous.handleAsync((unused, ignoredFailure) -> {
                    try {
                        return task.call();
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    } finally {
                        depth.decrementAndGet();
                    }
                }, executor);
                // Chain regardless of failure: the next enqueue waits on a
                // future that completes whether or not this task threw.
                tail = result.exceptionally(t -> null);
            }
            return result;
        }

        int queueDepth() {
            return depth.get();
        }

        @Override
        public String toString() {
            return "Lane[" + id + ", depth=" + depth.get() + "]";
        }
    }
}
