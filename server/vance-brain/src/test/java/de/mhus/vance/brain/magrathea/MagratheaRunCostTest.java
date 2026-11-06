package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.llmusage.LlmUsageService;
import de.mhus.vance.shared.llmusage.LlmUsageService.CostTotal;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.journal.ProcessSpawnedRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What a run has cost: the agents it spawned, and those of the runs it started below it — the way from a run to
 * its money goes through the journal, not through the task rows, which forget their process.
 */
class MagratheaRunCostTest {

    private final MagratheaJournalService journal = mock(MagratheaJournalService.class);
    private final LlmUsageService usage = mock(LlmUsageService.class);
    private final MagratheaRunCost cost = new MagratheaRunCost(journal, usage);

    @Test
    void theAgentsOfTheRunAndOfItsSubRunsAreAsked() {
        spawned("parent", "p1", "p2");
        spawned("child", "p3");
        when(journal.listRunStarts(any(), any(), any(Integer.class)))
                .thenReturn(List.of(
                        new MagratheaJournalService.RunStart(
                                "child",
                                StartRecord.builder()
                                        .parentMagratheaProcessId("parent")
                                        .build()),
                        new MagratheaJournalService.RunStart(
                                "other",
                                StartRecord.builder()
                                        .parentMagratheaProcessId("elsewhere")
                                        .build())));
        when(usage.costOfProcesses(eq("acme"), any())).thenReturn(List.of(new CostTotal("USD", 2_500_000L, 0L)));

        MagratheaRunCost.Cost result = cost.of("acme", "proj", "parent");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.forClass(Collection.class);
        org.mockito.Mockito.verify(usage).costOfProcesses(eq("acme"), asked.capture());
        assertThat(asked.getValue()).containsExactlyInAnyOrder("p1", "p2", "p3");
        assertThat(result.amount("USD")).isEqualTo(2.5);
    }

    @Test
    void aRunThatSpawnedNoAgentCostsNothingAndTheLedgerIsNotAsked() {
        when(journal.readAll(any(), any(), any(), eq(ProcessSpawnedRecord.class)))
                .thenReturn(List.of());
        when(journal.listRunStarts(any(), any(), any(Integer.class))).thenReturn(List.of());

        assertThat(cost.of("acme", "proj", "run").isEmpty()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(usage);
    }

    @Test
    void currenciesAreNeverAdded() {
        spawned("run", "p1");
        when(journal.listRunStarts(any(), any(), any(Integer.class))).thenReturn(List.of());
        when(usage.costOfProcesses(any(), any()))
                .thenReturn(List.of(new CostTotal("USD", 1_000_000L, 0L), new CostTotal("EUR", 2_000_000L, 1L)));

        MagratheaRunCost.Cost result = cost.of("acme", "proj", "run");

        assertThat(result.amount("USD")).isEqualTo(1.0);
        assertThat(result.amount("EUR")).isEqualTo(2.0);
        assertThat(result.totals()).hasSize(2);
    }

    @Test
    void aParentCycleDoesNotLoopForever() {
        spawned("a", "p1");
        spawned("b", "p2");
        when(journal.listRunStarts(any(), any(), any(Integer.class)))
                .thenReturn(List.of(
                        new MagratheaJournalService.RunStart(
                                "a",
                                StartRecord.builder()
                                        .parentMagratheaProcessId("b")
                                        .build()),
                        new MagratheaJournalService.RunStart(
                                "b",
                                StartRecord.builder()
                                        .parentMagratheaProcessId("a")
                                        .build())));
        when(usage.costOfProcesses(any(), any())).thenReturn(List.of());

        cost.of("acme", "proj", "a");

        org.mockito.Mockito.verify(usage).costOfProcesses(eq("acme"), any());
    }

    private void spawned(String runId, String... processIds) {
        List<ProcessSpawnedRecord> records = new java.util.ArrayList<>();
        for (String id : processIds) {
            records.add(ProcessSpawnedRecord.builder()
                    .taskId("t-" + id)
                    .processId(id)
                    .build());
        }
        when(journal.readAll(any(), any(), eq(runId), eq(ProcessSpawnedRecord.class)))
                .thenReturn(records);
    }
}
