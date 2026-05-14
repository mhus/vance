package de.mhus.vance.brain.hactar;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-project single-threaded executor. Serialises every Hactar
 * operation that mutates state for a given project so the journal and
 * the {@code hactar_tasks} queue cannot race against themselves —
 * task-execution, completion handling, claim dispatch all funnel
 * through the same thread (plan §10).
 *
 * <p>Lifetime is managed by {@link HactarProjectLaneManager}: lanes are
 * created lazily on first submission and shut down by the manager at
 * application stop.
 */
@Slf4j
public class HactarProjectLane {

    private final String projectId;
    private final ThreadPoolExecutor executor;

    HactarProjectLane(String projectId) {
        this.projectId = projectId;
        ThreadFactory tf = newThreadFactory(projectId);
        this.executor = new ThreadPoolExecutor(
                /* core */ 1,
                /* max  */ 1,
                /* keepAlive */ 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                tf);
    }

    public void submit(Runnable work) {
        executor.submit(() -> {
            try {
                work.run();
            } catch (RuntimeException ex) {
                log.error("Hactar lane[{}] task threw", projectId, ex);
            }
        });
    }

    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Hactar lane[{}] did not drain within 10s — forcing shutdown", projectId);
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
            t.setName("hactar-lane-" + projectId + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
