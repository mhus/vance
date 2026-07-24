package de.mhus.vance.brain.damogran;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-pod registry of async compose runs, keyed by {@code runId}. Lets the poll
 * endpoint find a run started by an earlier request (survives a UI refresh, not
 * a pod restart — by design). Bounded two ways: terminal runs are dropped once
 * their {@code finishedAt} is older than {@link #TERMINAL_TTL} (so they don't
 * linger even below the cap — the count-only eviction never triggered while
 * runs stayed under {@link #MAX_RUNS}), and the count cap still evicts the
 * oldest terminal run past {@link #MAX_RUNS}. Running runs are never dropped.
 */
@Service
public class ComposeRunRegistry {

    private static final int MAX_RUNS = 128;

    /** Terminal runs older than this (by {@code finishedAt}) are swept. */
    private static final Duration TERMINAL_TTL = Duration.ofMinutes(10);

    private final Map<String, ComposeRun> runs = new ConcurrentHashMap<>();

    public void register(ComposeRun run) {
        runs.put(run.runId(), run);
        evictExpiredTerminal(Instant.now());
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

    /** Drop terminal runs whose {@code finishedAt} is older than the TTL. */
    void evictExpiredTerminal(Instant now) {
        Instant cutoff = now.minus(TERMINAL_TTL);
        runs.values().removeIf(r -> {
            Instant finished = r.finishedAt();
            return r.isTerminal() && finished != null && finished.isBefore(cutoff);
        });
    }

    private void evictOldestTerminal() {
        runs.values().stream()
                .filter(ComposeRun::isTerminal)
                .min(Comparator.comparing(ComposeRun::startedAt))
                .map(ComposeRun::runId)
                .ifPresent(runs::remove);
    }
}
