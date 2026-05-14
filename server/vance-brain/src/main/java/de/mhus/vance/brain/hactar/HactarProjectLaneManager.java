package de.mhus.vance.brain.hactar;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Owns the per-project {@link HactarProjectLane} pool. Creates lanes
 * lazily on first submission; shuts them all down on application stop.
 *
 * <p>One lane per {@code projectId}: every Hactar operation for that
 * project — task execution, completion handling, claim dispatch — must
 * go through {@link #submit} so the journal sees a single writer per
 * project at a time (plan §10).
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@Slf4j
public class HactarProjectLaneManager {

    private final Map<String, HactarProjectLane> lanes = new ConcurrentHashMap<>();

    public void submit(String projectId, Runnable work) {
        lanes.computeIfAbsent(projectId, HactarProjectLane::new).submit(work);
    }

    public int pendingDepth(String projectId) {
        HactarProjectLane lane = lanes.get(projectId);
        return lane == null ? 0 : lane.pendingDepth();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down {} Hactar lane(s)", lanes.size());
        for (Map.Entry<String, HactarProjectLane> e : lanes.entrySet()) {
            e.getValue().shutdown();
        }
        lanes.clear();
    }
}
