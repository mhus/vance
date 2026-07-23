package de.mhus.vance.brain.magrathea;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Owns the per-project {@link MagratheaProjectLane} pool. Creates lanes
 * lazily on first submission; shuts them all down on application stop.
 *
 * <p>One lane per {@code projectId}: every Magrathea operation for that
 * project — task execution, completion handling, claim dispatch — must
 * go through {@link #submit} so the journal sees a single writer per
 * project at a time (plan §10).
 *
 * <p>Each lane carries its own single-thread executor, so a pod that
 * touches thousands of projects would otherwise accumulate thousands of
 * idle threads forever. A scheduled sweep evicts lanes that have been
 * idle past {@link #IDLE_TIMEOUT}; the next submission for that project
 * simply recreates one. Submission and eviction both go through
 * {@link ConcurrentHashMap#compute}, so they share the per-key lock and
 * an eviction can never race a concurrent submit onto a lane it is about
 * to shut down (code-review Phase 2).
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@Slf4j
public class MagratheaProjectLaneManager {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);
    private static final long SWEEP_INTERVAL_MS = 300_000L;

    private final Map<String, MagratheaProjectLane> lanes = new ConcurrentHashMap<>();

    /**
     * Enqueue {@code work} on the project lane and return its {@link Future}
     * so the caller can await + observe failures (code-review Phase 2).
     */
    public java.util.concurrent.Future<?> submitTracked(String projectId, Runnable work) {
        java.util.concurrent.Future<?>[] holder = new java.util.concurrent.Future<?>[1];
        lanes.compute(projectId, (id, lane) -> {
            MagratheaProjectLane l = (lane == null) ? new MagratheaProjectLane(id) : lane;
            holder[0] = l.submitTracked(work);
            return l;
        });
        return holder[0];
    }

    public void submit(String projectId, Runnable work) {
        lanes.compute(projectId, (id, lane) -> {
            MagratheaProjectLane l = (lane == null) ? new MagratheaProjectLane(id) : lane;
            l.submit(work);
            return l;
        });
    }

    public int pendingDepth(String projectId) {
        MagratheaProjectLane lane = lanes.get(projectId);
        return lane == null ? 0 : lane.pendingDepth();
    }

    /** Evict lanes idle longer than {@link #IDLE_TIMEOUT}. */
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS, initialDelay = SWEEP_INTERVAL_MS)
    public void sweepIdleLanes() {
        evictIdleLanesBefore(Instant.now().minus(IDLE_TIMEOUT));
    }

    /** Package-private core so tests can drive the threshold directly. */
    void evictIdleLanesBefore(Instant idleBefore) {
        for (String projectId : lanes.keySet()) {
            lanes.compute(projectId, (id, lane) -> {
                if (lane != null && lane.isEvictable(idleBefore)) {
                    lane.shutdown();
                    log.debug("Evicted idle Magrathea lane[{}]", id);
                    return null; // remove from map
                }
                return lane;
            });
        }
    }

    /** Package-private for tests. */
    int laneCount() {
        return lanes.size();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down {} Magrathea lane(s)", lanes.size());
        for (Map.Entry<String, MagratheaProjectLane> e : lanes.entrySet()) {
            e.getValue().shutdown();
        }
        lanes.clear();
    }
}
