package de.mhus.vance.brain.magrathea;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-project single-threaded executor. Serialises every Magrathea
 * operation that mutates state for a given project so the journal and
 * the {@code magrathea_tasks} queue cannot race against themselves —
 * task-execution, completion handling, claim dispatch all funnel
 * through the same thread (plan §10).
 *
 * <p>Lifetime is managed by {@link MagratheaProjectLaneManager}: lanes are
 * created lazily on first submission and shut down by the manager at
 * application stop.
 */
@Slf4j
public class MagratheaProjectLane {

    /**
     * The projectId of the lane whose worker thread is currently executing,
     * or {@code null} on any other thread. Set while a lane runs work so a
     * re-entrant submission from the lane thread itself can be detected and
     * run inline instead of dead-locking on the single lane thread
     * (code-review-2 B2).
     */
    private static final ThreadLocal<String> CURRENT_LANE = new ThreadLocal<>();

    /** True when the calling thread is the worker thread of {@code projectId}'s lane. */
    static boolean isCurrentThreadOnLane(String projectId) {
        return projectId.equals(CURRENT_LANE.get());
    }

    private final String projectId;
    private final ThreadPoolExecutor executor;
    private volatile Instant lastActivityAt;

    MagratheaProjectLane(String projectId) {
        this.projectId = projectId;
        this.lastActivityAt = Instant.now();
        ThreadFactory tf = newThreadFactory(projectId);
        this.executor = new ThreadPoolExecutor(
                /* core */ 1,
                /* max  */ 1,
                /* keepAlive */ 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                tf);
    }

    public void submit(Runnable work) {
        lastActivityAt = Instant.now();
        executor.submit(marked(() -> {
            try {
                work.run();
            } catch (RuntimeException ex) {
                log.error("Magrathea lane[{}] task threw", projectId, ex);
            }
        }));
    }

    /**
     * Wrap {@code work} so the worker thread advertises which lane it is
     * running via {@link #CURRENT_LANE} for the duration — enabling
     * re-entrant, in-lane submissions to run inline (see
     * {@link #isCurrentThreadOnLane}). The previous value is restored so
     * nested runs stay correct.
     */
    private Runnable marked(Runnable work) {
        return () -> {
            String previous = CURRENT_LANE.get();
            CURRENT_LANE.set(projectId);
            try {
                work.run();
            } finally {
                if (previous == null) {
                    CURRENT_LANE.remove();
                } else {
                    CURRENT_LANE.set(previous);
                }
            }
        };
    }

    /**
     * Like {@link #submit} but returns the {@link Future} so the caller can
     * await completion and observe failures instead of having them
     * swallowed. Used by {@code start()} so the runId is returned only
     * after the start-records are journalled (code-review Phase 2).
     */
    public java.util.concurrent.Future<?> submitTracked(Runnable work) {
        lastActivityAt = Instant.now();
        return executor.submit(marked(work));
    }

    /**
     * True when the lane has no queued work, nothing running, and its
     * last submission is older than {@code idleBefore} — safe for the
     * manager to evict. Callers must evaluate this under the manager's
     * per-key map lock so a concurrent submit cannot slip in between the
     * check and the shutdown (see {@code MagratheaProjectLaneManager}).
     */
    boolean isEvictable(Instant idleBefore) {
        return executor.getQueue().isEmpty()
                && executor.getActiveCount() == 0
                && lastActivityAt.isBefore(idleBefore);
    }

    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Magrathea lane[{}] did not drain within 10s — forcing shutdown", projectId);
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    int pendingDepth() {
        return executor.getQueue().size();
    }

    private static ThreadFactory newThreadFactory(String projectId) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName("magrathea-lane-" + projectId + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
