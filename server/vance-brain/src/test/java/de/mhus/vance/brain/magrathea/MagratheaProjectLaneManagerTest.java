package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Idle-lane eviction (code-review Phase 2 MEDIUM): a pod that touches
 * many projects must not accumulate one live thread per project forever.
 */
class MagratheaProjectLaneManagerTest {

    private final MagratheaProjectLaneManager manager = new MagratheaProjectLaneManager();

    @Test
    void idle_lane_is_evicted() throws Exception {
        manager.submitTracked("proj-a", () -> { }).get(2, TimeUnit.SECONDS);
        assertThat(manager.laneCount()).isEqualTo(1);

        // Threshold in the future → the lane's last activity is "before"
        // it. Poll the evict so the worker thread has settled to idle
        // (activeCount back to 0) — avoids a post-completion timing race.
        long deadline = System.currentTimeMillis() + 2000;
        while (manager.laneCount() > 0 && System.currentTimeMillis() < deadline) {
            manager.evictIdleLanesBefore(Instant.now().plusSeconds(60));
            if (manager.laneCount() == 0) break;
            Thread.sleep(10);
        }

        assertThat(manager.laneCount()).isZero();
    }

    @Test
    void running_lane_is_not_evicted() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        manager.submit("proj-b", () -> {
            started.countDown();
            try {
                hold.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        // Even with a far-future threshold, a lane with a task in flight
        // must not be evicted.
        manager.evictIdleLanesBefore(Instant.now().plusSeconds(60));
        assertThat(manager.laneCount()).isEqualTo(1);

        hold.countDown();
    }

    @Test
    void recently_active_lane_is_not_evicted() {
        manager.submit("proj-c", () -> { });

        // Threshold in the past → last activity is after it → keep.
        manager.evictIdleLanesBefore(Instant.now().minusSeconds(60));

        assertThat(manager.laneCount()).isEqualTo(1);
    }

    /**
     * Security/liveness regression (code-review-2 B2): a workflow_task runs ON
     * the project lane and spawns a sub-workflow via
     * {@code MagratheaWorkflowService.start}, which does
     * {@code submitTracked(sameProject).get()}. The single lane thread cannot
     * run the queued start while it is blocked on .get(), so the old code
     * dead-locked (30s timeout → orphaned sub-run). A re-entrant, same-lane
     * submission must now run inline and return immediately.
     */
    @Test
    void reentrant_submitTracked_runsInline_notDeadlock() throws Exception {
        AtomicBoolean innerRan = new AtomicBoolean(false);
        AtomicReference<Throwable> innerErr = new AtomicReference<>();

        Future<?> outer = manager.submitTracked("proj-re", () -> {
            try {
                // Re-entrant submit onto the SAME lane from the lane thread.
                manager.submitTracked("proj-re", () -> innerRan.set(true))
                        .get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                innerErr.set(e);
            }
        });

        outer.get(3, TimeUnit.SECONDS); // TimeoutException here before the fix
        assertThat(innerRan).isTrue();
        assertThat(innerErr.get()).isNull();
    }

    @Test
    void reentrant_submitTracked_propagatesFailureViaGet() throws Exception {
        AtomicReference<Throwable> caught = new AtomicReference<>();

        Future<?> outer = manager.submitTracked("proj-rf", () -> {
            try {
                manager.submitTracked("proj-rf",
                        () -> { throw new IllegalStateException("boom"); })
                        .get(2, TimeUnit.SECONDS);
            } catch (Throwable t) {
                caught.set(t);
            }
        });

        outer.get(3, TimeUnit.SECONDS);
        assertThat(caught.get()).isInstanceOf(ExecutionException.class);
        assertThat(caught.get().getCause()).isInstanceOf(IllegalStateException.class);
    }
}
