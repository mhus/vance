package de.mhus.vance.brain.damogran;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * In-pod registry of async compose runs, keyed by {@code runId}. Lets the poll
 * endpoint find a run started by an earlier request (survives a UI refresh, not
 * a pod restart — by design). Bounded: once over {@link #MAX_RUNS}, the oldest
 * <em>terminal</em> run is evicted (running ones are never dropped).
 */
@Service
public class ComposeRunRegistry {

    private static final int MAX_RUNS = 128;

    private final Map<String, ComposeRun> runs = new ConcurrentHashMap<>();

    public void register(ComposeRun run) {
        runs.put(run.runId(), run);
        if (runs.size() > MAX_RUNS) {
            evictOldestTerminal();
        }
    }

    /** Look up a run by id, scoped to the caller's tenant + project. */
    public Optional<ComposeRun> find(String tenantId, String projectId, String runId) {
        ComposeRun run = runs.get(runId);
        if (run == null
                || !run.tenantId().equals(tenantId)
                || !run.projectId().equals(projectId)) {
            return Optional.empty();
        }
        return Optional.of(run);
    }

    private void evictOldestTerminal() {
        runs.values().stream()
                .filter(ComposeRun::isTerminal)
                .min(Comparator.comparing(ComposeRun::startedAt))
                .map(ComposeRun::runId)
                .ifPresent(runs::remove);
    }
}
