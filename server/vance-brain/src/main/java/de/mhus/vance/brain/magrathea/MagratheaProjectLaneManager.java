package de.mhus.vance.brain.magrathea;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Owns the per-project {@link MagratheaProjectLane} pool. Creates lanes
 * lazily on first submission; shuts them all down on application stop.
 *
 * <p>One lane per {@code projectId}: every Magrathea operation for that
 * project — task execution, completion handling, claim dispatch — must
 * go through {@link #submit} so the journal sees a single writer per
 * project at a time (plan §10).
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@Slf4j
public class MagratheaProjectLaneManager {

    private final Map<String, MagratheaProjectLane> lanes = new ConcurrentHashMap<>();

    public void submit(String projectId, Runnable work) {
        lanes.computeIfAbsent(projectId, MagratheaProjectLane::new).submit(work);
    }

    public int pendingDepth(String projectId) {
        MagratheaProjectLane lane = lanes.get(projectId);
        return lane == null ? 0 : lane.pendingDepth();
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
