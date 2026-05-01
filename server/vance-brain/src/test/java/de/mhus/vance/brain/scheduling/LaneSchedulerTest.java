package de.mhus.vance.brain.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the per-lane FIFO contract — the safety property that two
 * tasks on the same lane never run concurrently, plus the recovery
 * property that a failed task does not poison the lane.
 */
class LaneSchedulerTest {

    private LaneScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new LaneScheduler();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void tasksOnSameLane_runSerially_neverOverlap() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        int taskCount = 32;

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            futures.add(scheduler.submit("lane-1", () -> {
                int now = inFlight.incrementAndGet();
                maxInFlight.updateAndGet(prev -> Math.max(prev, now));
                try {
                    Thread.sleep(2); // amplify overlap window
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);

        assertThat(maxInFlight.get())
                .as("Same lane must never have >1 task running at the same time")
                .isEqualTo(1);
    }

    @Test
    void tasksOnDifferentLanes_canRunInParallel() throws Exception {
        // Two lanes, each task signals it has started, then waits on a
        // shared latch. If lanes are properly independent both tasks
        // start before the latch releases. If they were serialised
        // through one queue we'd deadlock and time out.
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> a = scheduler.submit("lane-A", () -> {
            bothStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
        CompletableFuture<Void> b = scheduler.submit("lane-B", () -> {
            bothStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });

        boolean started = bothStarted.await(5, TimeUnit.SECONDS);
        assertThat(started).as("Both lanes must run concurrently").isTrue();
        release.countDown();

        CompletableFuture.allOf(a, b).get(5, TimeUnit.SECONDS);
    }

    @Test
    void taskOrdering_onSameLane_isFifo() throws Exception {
        List<Integer> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        int taskCount = 50;

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            int n = i;
            futures.add(scheduler.submit("lane-1", () -> { order.add(n); }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);

        for (int i = 0; i < taskCount; i++) {
            assertThat(order.get(i)).as("FIFO position %d", i).isEqualTo(i);
        }
    }

    @Test
    void failedTask_doesNotPoisonLane_subsequentTaskRuns() throws Exception {
        AtomicInteger ran = new AtomicInteger();

        CompletableFuture<Void> failed = scheduler.submit("lane-1", () -> {
            throw new IllegalStateException("intentional");
        });
        CompletableFuture<Integer> next = scheduler.submit("lane-1", () -> ran.incrementAndGet());

        // Failure surfaces to the submitter…
        assertThatThrownBy(() -> failed.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);

        // …but the lane keeps draining.
        assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    }

    @Test
    void laneCount_growsWithDistinctLanes() throws Exception {
        scheduler.submit("lane-A", () -> {}).get(5, TimeUnit.SECONDS);
        scheduler.submit("lane-B", () -> {}).get(5, TimeUnit.SECONDS);
        scheduler.submit("lane-A", () -> {}).get(5, TimeUnit.SECONDS); // not a new lane

        assertThat(scheduler.laneCount()).isEqualTo(2);
    }

    @Test
    void forget_dropsLaneBookkeeping_butActiveTaskFinishes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<String> running = scheduler.submit("lane-X", () -> {
            entered.countDown();
            release.await();
            return "ok";
        });
        entered.await(5, TimeUnit.SECONDS);

        scheduler.forget("lane-X");
        assertThat(scheduler.laneCount()).isEqualTo(0);
        // A fresh submit with the same id starts a brand-new lane.
        scheduler.submit("lane-X", () -> {}); // implicit new Lane
        assertThat(scheduler.laneCount()).isEqualTo(1);

        release.countDown();
        assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
    }

    @Test
    void runnableOverload_completesSuccessfully() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        scheduler.submit("lane-1", (Runnable) ran::incrementAndGet)
                .get(5, TimeUnit.SECONDS);
        assertThat(ran.get()).isEqualTo(1);
    }
}
