package de.mhus.vance.brain.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaProcessDto;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StateEnteredRecord;
import de.mhus.vance.shared.magrathea.journal.TaskResultRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MagratheaRunSourceTest {

    private final MagratheaJournalService journal = mock(MagratheaJournalService.class);
    private final MagratheaStateProjector projector = mock(MagratheaStateProjector.class);
    private final MagratheaRunSource source = new MagratheaRunSource(journal, projector);

    @Test
    void listsProjectedRunsWithACompositeId() {
        when(journal.listRunIds(eq("acme"), eq("proj"), anyInt())).thenReturn(List.of("r1"));
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.RUNNING)));

        assertThat(source.list("acme", "proj", 10))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getRunId()).isEqualTo("workflow:r1");
                    assertThat(r.getSource()).isEqualTo("workflow");
                    assertThat(r.getStatus()).isEqualTo(RunStatus.RUNNING);
                });
    }

    @Test
    void mapsTheFiveRunStatuses() {
        assertThat(statusOf(MagratheaRunStatus.RUNNING)).isEqualTo(RunStatus.RUNNING);
        assertThat(statusOf(MagratheaRunStatus.PAUSED)).isEqualTo(RunStatus.PAUSED);
        assertThat(statusOf(MagratheaRunStatus.DONE)).isEqualTo(RunStatus.DONE);
        assertThat(statusOf(MagratheaRunStatus.FAILED)).isEqualTo(RunStatus.FAILED);
        // TERMINATED is the run's "someone stopped it", which the shared
        // vocabulary calls STOPPED.
        assertThat(statusOf(MagratheaRunStatus.TERMINATED)).isEqualTo(RunStatus.STOPPED);
    }

    @Test
    void pairsEnteredStatesWithTheirTaskResults() {
        // The journal is a flat event log — the step list is reconstructed
        // by matching each entered state to the result that ended it.
        stubDetail("r1");
        when(journal.readAll("acme", "proj", "r1", StateEnteredRecord.class)).thenReturn(List.of(
                StateEnteredRecord.builder().state("work").build(),
                StateEnteredRecord.builder().state("review").build()));
        when(journal.readAll("acme", "proj", "r1", TaskResultRecord.class)).thenReturn(List.of(
                TaskResultRecord.builder().state("work").outcome("success").build()));

        var steps = source.get("acme", "proj", "r1").orElseThrow().getSteps();

        assertThat(steps).extracting("name").containsExactly("work", "review");
        assertThat(steps.get(0).getOutcome()).isEqualTo("success");
        // Still the current step — no result yet, so no outcome.
        assertThat(steps.get(1).getOutcome()).isNull();
    }

    @Test
    void linksToTheDocumentTheRunCameFrom() {
        stubDetail("r1");
        when(journal.readLast("acme", "proj", "r1", StartRecord.class)).thenReturn(
                Optional.of(StartRecord.builder()
                        .workflowName("helloworld").sourcePath("drafts/my-flow.yaml").build()));

        assertThat(source.get("acme", "proj", "r1").orElseThrow().getLinks())
                .singleElement()
                .satisfies(l -> assertThat(l.getTarget()).isEqualTo("drafts/my-flow.yaml"));
    }

    @Test
    void withoutASourcePathTheLinkFallsBackToTheCascadePath() {
        // Name-started runs carry no path; the cascade location is where
        // that definition lives by definition.
        stubDetail("r1");
        when(journal.readLast("acme", "proj", "r1", StartRecord.class)).thenReturn(
                Optional.of(StartRecord.builder().workflowName("helloworld").build()));

        assertThat(source.get("acme", "proj", "r1").orElseThrow().getLinks())
                .singleElement()
                .satisfies(l -> assertThat(l.getTarget())
                        .isEqualTo("_vance/workflows/helloworld.yaml"));
    }

    @Test
    void aRunOfAnotherProjectReadsAsAbsent() {
        MagratheaProcessDto foreign = run("r1", MagratheaRunStatus.RUNNING);
        foreign.setProjectId("other");
        when(projector.project(any(), any(), eq("r1"))).thenReturn(Optional.of(foreign));

        assertThat(source.get("acme", "proj", "r1")).isEmpty();
    }

    private RunStatus statusOf(MagratheaRunStatus status) {
        when(journal.listRunIds(eq("acme"), eq("proj"), anyInt())).thenReturn(List.of("r1"));
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", status)));
        return source.list("acme", "proj", 10).get(0).getStatus();
    }

    private void stubDetail(String runId) {
        when(projector.project("acme", "proj", runId))
                .thenReturn(Optional.of(run(runId, MagratheaRunStatus.RUNNING)));
        when(journal.listRunIds(eq("acme"), eq("proj"), anyInt())).thenReturn(List.of(runId));
    }

    private static MagratheaProcessDto run(String runId, MagratheaRunStatus status) {
        MagratheaProcessDto dto = new MagratheaProcessDto();
        dto.setWorkflowRunId(runId);
        dto.setWorkflowName("helloworld");
        dto.setTenantId("acme");
        dto.setProjectId("proj");
        dto.setStatus(status);
        dto.setCurrentState("work");
        dto.setVars(Map.of("work_result", "hi"));
        return dto;
    }
}
