package de.mhus.vance.brain.magrathea;

import de.mhus.vance.shared.llmusage.LlmUsageService;
import de.mhus.vance.shared.llmusage.LlmUsageService.CostTotal;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.journal.ProcessSpawnedRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * What a workflow run has cost: the model calls of the agents it spawned, and of the runs it started below it.
 *
 * <p>The link is the journal: an {@code agent_task} writes a {@link ProcessSpawnedRecord} when it spawns, so the
 * processes of a run are known even after the task row has forgotten them. Their calls are in the usage ledger
 * under the process id ({@link LlmUsageService#costOfProcesses}). A sub-run — a for-each element — is a run of
 * its own with its own spawns, found through the parent pointer on its {@code StartRecord}, and counts toward
 * the run that started it.
 *
 * <p>What it does not see: a tool that makes a model call without a process (a light single-shot call has none),
 * and calls older than the ledger's detail retention. It is the cost of the <em>agents</em> of a run, which is
 * where a run's money goes; it is stated as such wherever it is shown.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class MagratheaRunCost {

    /** How many of the project's most recent runs are searched for children — there is no index on the parent. */
    private static final int CHILD_SCAN_LIMIT = 500;

    /** How deep sub-runs are followed, against a corrupt parent cycle. */
    private static final int MAX_DEPTH = 6;

    private final MagratheaJournalService journalService;
    private final LlmUsageService usageService;

    /** What a run cost, per currency. */
    public record Cost(List<CostTotal> totals) {

        /** Amount in the given currency, {@code 0} if none was booked. */
        public double amount(String currency) {
            for (CostTotal t : totals) {
                if (currency.equalsIgnoreCase(t.currency())) return LlmUsageService.fromMicros(t.costMicros());
            }
            return 0d;
        }

        public boolean isEmpty() {
            return totals.isEmpty();
        }
    }

    /** The cost of the run and of every run below it. */
    public Cost of(String tenantId, String projectId, String runId) {
        Set<String> processes = new LinkedHashSet<>();
        // The project's recent runs are read once and grouped by parent; the walk down the tree is then in memory.
        Map<String, List<String>> childrenByParent = new HashMap<>();
        for (MagratheaJournalService.RunStart candidate :
                journalService.listRunStarts(tenantId, projectId, CHILD_SCAN_LIMIT)) {
            String parent = candidate.start().getParentMagratheaProcessId();
            if (parent != null) {
                childrenByParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(candidate.workflowRunId());
            }
        }
        collect(tenantId, projectId, runId, childrenByParent, processes, new LinkedHashSet<>(), 0);
        if (processes.isEmpty()) return new Cost(List.of());
        // Several currencies stay several entries; none is converted into another.
        Map<String, long[]> byCurrency = new LinkedHashMap<>();
        for (CostTotal t : usageService.costOfProcesses(tenantId, processes)) {
            long[] sums = byCurrency.computeIfAbsent(t.currency(), k -> new long[2]);
            sums[0] += t.costMicros();
            sums[1] += t.unpricedCalls();
        }
        List<CostTotal> out = new ArrayList<>();
        byCurrency.forEach((currency, sums) -> out.add(new CostTotal(currency, sums[0], sums[1])));
        return new Cost(List.copyOf(out));
    }

    /** Whether the run spawned any agent at all — the cheap question before the ledger is asked. */
    public boolean spawnedAgents(String tenantId, String projectId, String runId) {
        return !journalService
                .readAll(tenantId, projectId, runId, ProcessSpawnedRecord.class)
                .isEmpty();
    }

    private void collect(
            String tenantId,
            String projectId,
            String runId,
            Map<String, List<String>> childrenByParent,
            Set<String> processes,
            Set<String> visited,
            int depth) {
        if (depth > MAX_DEPTH || !visited.add(runId)) return;
        for (ProcessSpawnedRecord spawned :
                journalService.readAll(tenantId, projectId, runId, ProcessSpawnedRecord.class)) {
            processes.add(spawned.getProcessId());
        }
        for (String child : childrenByParent.getOrDefault(runId, List.of())) {
            collect(tenantId, projectId, child, childrenByParent, processes, visited, depth + 1);
        }
    }
}
