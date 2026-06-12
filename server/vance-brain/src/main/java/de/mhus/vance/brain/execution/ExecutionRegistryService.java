package de.mhus.vance.brain.execution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Brain-central, in-memory index of every shell execution the brain
 * knows about — its own {@code ExecManager} jobs and (later, via
 * {@code EXEC_EVENT} frames) every foot-side job too. The registry
 * never owns a process; it only knows about them.
 *
 * <p>Thread-safety: all mutators write through a single
 * {@link ConcurrentHashMap}; entries are immutable {@link
 * ExecutionRegistryEntry} records, so readers see a consistent
 * snapshot per id.
 */
@Service
@Slf4j
public class ExecutionRegistryService {

    private final Map<String, ExecutionRegistryEntry> entries = new ConcurrentHashMap<>();

    /** Insert or replace an entry. Idempotent on {@link ExecutionRegistryEntry#executionId()}. */
    public void register(ExecutionRegistryEntry entry) {
        entries.put(entry.executionId(), entry);
    }

    /**
     * Apply progress fields to an existing entry. No-op if the id is
     * unknown — start events arrive before progress in the sane case,
     * but a foot reconnect can deliver stale ticks.
     */
    public void updateProgress(
            String executionId,
            Instant lastOutputAt,
            ExecutionStatus status,
            @Nullable Integer exitCode,
            @Nullable Instant endedAt) {
        entries.computeIfPresent(executionId, (id, prev) ->
                prev.withProgress(lastOutputAt, status, exitCode, endedAt));
    }

    public Optional<ExecutionRegistryEntry> find(String executionId) {
        return Optional.ofNullable(entries.get(executionId));
    }

    /**
     * Attaches the owning think-process id to an existing entry — used
     * for foot-spawned jobs where the STARTED frame doesn't carry the
     * brain-side processId. Returns {@code true} when the entry was
     * found and updated; {@code false} when the executionId is
     * unknown (caller is too early — the STARTED frame hasn't been
     * processed yet — or the entry has already been dropped).
     */
    public boolean attachProcessId(String executionId, String processId) {
        ExecutionRegistryEntry prev = entries.computeIfPresent(executionId,
                (id, e) -> e.withProcessId(processId));
        return prev != null;
    }

    /** Snapshot view, oldest-first by {@code startedAt}. */
    public List<ExecutionRegistryEntry> list(ExecutionScopeFilter filter) {
        return list(filter, Map.of());
    }

    /**
     * Snapshot view with optional label selector. {@code labelSelector}
     * entries are AND-combined as equals-matches against
     * {@link ExecutionRegistryEntry#labels()}; an entry without the
     * required key never matches. Empty selector behaves like
     * {@link #list(ExecutionScopeFilter)}.
     */
    public List<ExecutionRegistryEntry> list(
            ExecutionScopeFilter filter, Map<String, String> labelSelector) {
        Collection<ExecutionRegistryEntry> snap = entries.values();
        List<ExecutionRegistryEntry> out = new ArrayList<>(snap.size());
        for (ExecutionRegistryEntry e : snap) {
            if (!filter.matches(e)) continue;
            if (!matchesLabels(e, labelSelector)) continue;
            out.add(e);
        }
        out.sort(Comparator.comparing(ExecutionRegistryEntry::startedAt));
        return out;
    }

    private static boolean matchesLabels(
            ExecutionRegistryEntry e, Map<String, String> labelSelector) {
        if (labelSelector.isEmpty()) return true;
        Map<String, String> labels = e.labels();
        for (Map.Entry<String, String> req : labelSelector.entrySet()) {
            if (!req.getValue().equals(labels.get(req.getKey()))) return false;
        }
        return true;
    }

    /**
     * True when any {@link ExecutionStatus#RUNNING} entry belongs to
     * the given session. Used by {@code SessionIdleSweeper} to keep a
     * session alive while a tracked script-execution still runs even
     * though no think-process is in a RUNNING state.
     */
    public boolean hasActiveJobsForSession(
            @Nullable String tenantId, String sessionId) {
        for (ExecutionRegistryEntry e : entries.values()) {
            if (e.status() != ExecutionStatus.RUNNING) continue;
            if (!sessionId.equals(e.sessionId())) continue;
            if (tenantId != null && !tenantId.equals(e.tenantId())) continue;
            return true;
        }
        return false;
    }

    /** Drop every entry owned by the given foot client — used on disconnect. */
    public int removeByFootClient(String clientId) {
        String label = new ExecutionOwner.Foot(clientId).label();
        int before = entries.size();
        entries.values().removeIf(e -> e.owner().label().equals(label));
        return before - entries.size();
    }

    /** For tests. */
    int size() {
        return entries.size();
    }
}
