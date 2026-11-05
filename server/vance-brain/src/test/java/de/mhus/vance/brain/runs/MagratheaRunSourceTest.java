package de.mhus.vance.brain.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaProcessDto;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.runs.RunAction;
import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StateEnteredRecord;
import de.mhus.vance.shared.magrathea.journal.TaskResultRecord;
import de.mhus.vance.shared.magrathea.journal.TaskStartedRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MagratheaRunSourceTest {

    private final MagratheaJournalService journal = mock(MagratheaJournalService.class);
    private final MagratheaStateProjector projector = mock(MagratheaStateProjector.class);
    private final de.mhus.vance.brain.magrathea.MagratheaWorkflowService workflows =
            mock(de.mhus.vance.brain.magrathea.MagratheaWorkflowService.class);
    private final de.mhus.vance.brain.magrathea.MagratheaGateChatAnswerService gateAnswers =
            mock(de.mhus.vance.brain.magrathea.MagratheaGateChatAnswerService.class);
    private final MagratheaRunSource source = new MagratheaRunSource(
            journal,
            projector,
            workflows,
            mock(de.mhus.vance.shared.session.SessionService.class),
            mock(de.mhus.vance.shared.permission.PermissionService.class),
            gateAnswers);

    @Test
    void listsProjectedRunsWithACompositeId() {
        when(journal.listRunIds(eq("acme"), eq("proj"), anyInt())).thenReturn(List.of("r1"));
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.RUNNING)));

        assertThat(source.list("acme", "proj", 10)).singleElement().satisfies(r -> {
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
        stubJournal(
                "r1",
                StateEnteredRecord.builder().state("work").build(),
                TaskResultRecord.builder().state("work").outcome("success").build(),
                StateEnteredRecord.builder().state("review").build());

        var steps = source.get("acme", "proj", "r1").orElseThrow().getSteps();

        assertThat(steps).extracting("name").containsExactly("work", "review");
        assertThat(steps.get(0).getOutcome()).isEqualTo("success");
        // Still the current step — no result yet, so no outcome.
        assertThat(steps.get(1).getOutcome()).isNull();
    }

    @Test
    void aStepCarriesItsTypeDurationTimeAndOutput() {
        stubDetail("r1");
        stubJournal(
                "r1",
                StateEnteredRecord.builder().state("work").build(),
                TaskStartedRecord.builder()
                        .state("work")
                        .taskType(MagratheaTaskType.AGENT_TASK)
                        .build(),
                TaskResultRecord.builder()
                        .state("work")
                        .outcome("success")
                        .durationMs(1234)
                        .output(new ObjectMapper().readTree("{\"summary\":\"done\"}"))
                        .build());

        var step = source.get("acme", "proj", "r1").orElseThrow().getSteps().get(0);

        assertThat(step.getKind()).isEqualTo("agent_task");
        assertThat(step.getDurationMs()).isEqualTo(1234L);
        assertThat(step.getAt()).isEqualTo(JOURNAL_START);
        assertThat(step.getOutput()).contains("\"summary\"").contains("done");
        assertThat(step.isOutputTruncated()).isFalse();
    }

    @Test
    void aTextOutputIsShownAsTextNotAsQuotedJson() {
        stubDetail("r1");
        stubJournal(
                "r1",
                StateEnteredRecord.builder().state("work").build(),
                TaskResultRecord.builder()
                        .state("work")
                        .outcome("success")
                        .output(new ObjectMapper().valueToTree("a plain answer"))
                        .build());

        assertThat(source.get("acme", "proj", "r1")
                        .orElseThrow()
                        .getSteps()
                        .get(0)
                        .getOutput())
                .isEqualTo("a plain answer");
    }

    @Test
    void aLongOutputIsCutAndSaysSo() {
        stubDetail("r1");
        stubJournal(
                "r1",
                StateEnteredRecord.builder().state("work").build(),
                TaskResultRecord.builder()
                        .state("work")
                        .outcome("success")
                        .output(new ObjectMapper().valueToTree("x".repeat(25_000)))
                        .build());

        var step = source.get("acme", "proj", "r1").orElseThrow().getSteps().get(0);

        assertThat(step.getOutput()).hasSize(20_000);
        assertThat(step.isOutputTruncated()).isTrue();
    }

    @Test
    void aStateEnteredTwiceGetsAStepWithItsOwnResultEachTime() {
        // A loop re-enters the same state. Pairing by name alone would give
        // the last result to both rounds.
        stubDetail("r1");
        stubJournal(
                "r1",
                StateEnteredRecord.builder().state("write").build(),
                TaskResultRecord.builder().state("write").outcome("revise").build(),
                StateEnteredRecord.builder().state("write").build(),
                TaskResultRecord.builder().state("write").outcome("success").build());

        var steps = source.get("acme", "proj", "r1").orElseThrow().getSteps();

        assertThat(steps).extracting("outcome").containsExactly("revise", "success");
    }

    @Test
    void aFailedStepKeepsItsErrorMessageAsDetail() {
        stubDetail("r1");
        stubJournal(
                "r1",
                StateEnteredRecord.builder().state("act").build(),
                TaskResultRecord.builder()
                        .state("act")
                        .outcome("technical_error")
                        .errorMessage("HTTP 429")
                        .build());

        var step = source.get("acme", "proj", "r1").orElseThrow().getSteps().get(0);

        assertThat(step.getOutcome()).isEqualTo("technical_error");
        assertThat(step.getDetail()).isEqualTo("HTTP 429");
        assertThat(step.getOutput()).isNull();
    }

    @Test
    void aHaltedDebugRunOffersStepNextToResume() {
        var dto = run("r1", MagratheaRunStatus.PAUSED);
        dto.setDebug(true);
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(dto));

        var detail = source.get("acme", "proj", "r1").orElseThrow();

        assertThat(detail.getAllowedActions())
                .containsExactlyInAnyOrder(RunAction.STEP, RunAction.RESUME, RunAction.STOP);
        assertThat(detail.isDebug()).isTrue();
    }

    @Test
    void aPlainPausedRunOffersNoStep() {
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.PAUSED)));

        assertThat(source.allowedActions("acme", "proj", "r1"))
                .containsExactlyInAnyOrder(RunAction.RESUME, RunAction.STOP);
    }

    @Test
    void aRunningDebugRunOffersNoStepEither() {
        var dto = run("r1", MagratheaRunStatus.RUNNING);
        dto.setDebug(true);
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(dto));

        assertThat(source.allowedActions("acme", "proj", "r1"))
                .containsExactlyInAnyOrder(RunAction.PAUSE, RunAction.STOP);
    }

    @Test
    void performingStepStepsTheRun() {
        var dto = run("r1", MagratheaRunStatus.PAUSED);
        dto.setDebug(true);
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(dto));

        source.perform("acme", "proj", "r1", RunAction.STEP, "why");

        verify(workflows).stepRun("acme", "proj", "r1");
    }

    @Test
    void steppingARunThatDoesNotOfferItIsANoOp() {
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.PAUSED)));

        source.perform("acme", "proj", "r1", RunAction.STEP, "why");

        verify(workflows, never()).stepRun(any(), any(), any());
    }

    @Test
    void aDebuggableRunCarriesItsBreakpointsInTheDetail() {
        var dto = run("r1", MagratheaRunStatus.RUNNING);
        dto.setDebug(true);
        dto.setBreakpoints(List.of("review", "merge"));
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(dto));

        assertThat(source.get("acme", "proj", "r1").orElseThrow().getBreakpoints())
                .containsExactly("review", "merge");
    }

    @Test
    void breakpointsAreHandedToTheServiceForADebuggableRun() {
        var dto = run("r1", MagratheaRunStatus.RUNNING);
        dto.setDebug(true);
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(dto));

        source.setBreakpoints("acme", "proj", "r1", List.of("review"));

        verify(workflows).setBreakpoints("acme", "proj", "r1", List.of("review"));
    }

    @Test
    void breakpointsOnARunThatIsNotDebuggableAreANoOp() {
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.RUNNING)));

        source.setBreakpoints("acme", "proj", "r1", List.of("review"));

        verify(workflows, never()).setBreakpoints(any(), any(), any(), any());
    }

    @Test
    void breakpointsOnAnUnknownRunAreRefused() {
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> source.setBreakpoints("acme", "proj", "r1", List.of("review")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHaltedDebugRunOffersItsSimulationInTheDetail() {
        var dto = run("r1", MagratheaRunStatus.PAUSED);
        dto.setDebug(true);
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(dto));
        when(workflows.simulationFor("acme", "proj", "r1"))
                .thenReturn(Optional.of(new de.mhus.vance.brain.magrathea.MagratheaWorkflowService.Simulation(
                        "call",
                        de.mhus.vance.api.magrathea.MagratheaTaskType.TOOL_TASK,
                        List.of("success", "technical_error"))));

        var simulation = source.get("acme", "proj", "r1").orElseThrow().getSimulation();

        assertThat(simulation.getState()).isEqualTo("call");
        assertThat(simulation.getKind()).isEqualTo("tool_task");
        assertThat(simulation.getOutcomes()).containsExactly("success", "technical_error");
    }

    @Test
    void aRunThatIsGoingIsNotAskedForASimulation() {
        var dto = run("r1", MagratheaRunStatus.RUNNING);
        dto.setDebug(true);
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(dto));

        assertThat(source.get("acme", "proj", "r1").orElseThrow().getSimulation())
                .isNull();
        verify(workflows, never()).simulationFor(any(), any(), any());
    }

    @Test
    void simulatingIsHandedToTheServiceAsATree() {
        var dto = run("r1", MagratheaRunStatus.PAUSED);
        dto.setDebug(true);
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(dto));

        source.simulate("acme", "proj", "r1", "success", Map.of("ticket", "ABC-1"), null);

        var captor = org.mockito.ArgumentCaptor.forClass(tools.jackson.databind.JsonNode.class);
        verify(workflows).simulateRun(eq("acme"), eq("proj"), eq("r1"), eq("success"), captor.capture(), eq(null));
        assertThat(captor.getValue().get("ticket").asString()).isEqualTo("ABC-1");
    }

    @Test
    void aPausedRunThatIsNotDebuggableCanBeSkippedThroughSimulate() {
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.PAUSED)));

        source.simulate("acme", "proj", "r1", "success", null, null);

        // Skip task: the service decides whether something is held, not the debug flag.
        verify(workflows).simulateRun(eq("acme"), eq("proj"), eq("r1"), eq("success"), eq(null), eq(null));
    }

    @Test
    void aRunWaitingOnAnEndableTaskOffersAbort() {
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.RUNNING)));
        when(workflows.abortableTask("acme", "proj", "r1"))
                .thenReturn(Optional.of(de.mhus.vance.shared.magrathea.MagratheaTaskDocument.builder()
                        .id("t1")
                        .stateName("wait")
                        .build()));

        assertThat(source.allowedActions("acme", "proj", "r1"))
                .containsExactlyInAnyOrder(RunAction.PAUSE, RunAction.STOP, RunAction.ABORT);
    }

    @Test
    void aRunWithNothingToAbortOffersNoAbort() {
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.RUNNING)));

        assertThat(source.allowedActions("acme", "proj", "r1"))
                .containsExactlyInAnyOrder(RunAction.PAUSE, RunAction.STOP);
    }

    @Test
    void performingAbortAbortsTheTask() {
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.RUNNING)));
        when(workflows.abortableTask("acme", "proj", "r1"))
                .thenReturn(Optional.of(de.mhus.vance.shared.magrathea.MagratheaTaskDocument.builder()
                        .id("t1")
                        .build()));

        source.perform("acme", "proj", "r1", RunAction.ABORT, "why");

        verify(workflows).abortTask("acme", "proj", "r1", "why");
    }

    @Test
    void abortingARunThatOffersNoAbortIsANoOp() {
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.RUNNING)));

        source.perform("acme", "proj", "r1", RunAction.ABORT, "why");

        verify(workflows, never()).abortTask(any(), any(), any(), any());
    }

    @Test
    void aSimulatedStepIsMarkedAndHasNoDuration() {
        stubDetail("r1");
        stubJournal(
                "r1",
                StateEnteredRecord.builder().state("call").build(),
                TaskResultRecord.builder()
                        .state("call")
                        .outcome("success")
                        .durationMs(0)
                        .simulated(true)
                        .build());

        var step = source.get("acme", "proj", "r1").orElseThrow().getSteps().get(0);

        assertThat(step.isSimulated()).isTrue();
        assertThat(step.getDurationMs()).isNull();
    }

    @Test
    void linksToTheDocumentTheRunCameFrom() {
        stubDetail("r1");
        when(journal.readLast("acme", "proj", "r1", StartRecord.class))
                .thenReturn(Optional.of(StartRecord.builder()
                        .workflowName("helloworld")
                        .sourcePath("drafts/my-flow.yaml")
                        .build()));

        assertThat(source.get("acme", "proj", "r1").orElseThrow().getLinks())
                .singleElement()
                .satisfies(l -> assertThat(l.getTarget()).isEqualTo("drafts/my-flow.yaml"));
    }

    @Test
    void theDetailCarriesTheDefinitionTheRunFroze() {
        // The picture of the plan must be the one the run follows, not the
        // document as it reads by now.
        stubDetail("r1");
        when(journal.readLast("acme", "proj", "r1", StartRecord.class))
                .thenReturn(Optional.of(StartRecord.builder()
                        .workflowName("helloworld")
                        .definitionYaml("start: a\nstates: {}\n")
                        .build()));

        assertThat(source.get("acme", "proj", "r1").orElseThrow().getDefinition())
                .isEqualTo("start: a\nstates: {}\n");
    }

    @Test
    void withoutASourcePathTheLinkFallsBackToTheCascadePath() {
        // Name-started runs carry no path; the cascade location is where
        // that definition lives by definition.
        stubDetail("r1");
        when(journal.readLast("acme", "proj", "r1", StartRecord.class))
                .thenReturn(Optional.of(
                        StartRecord.builder().workflowName("helloworld").build()));

        assertThat(source.get("acme", "proj", "r1").orElseThrow().getLinks())
                .singleElement()
                .satisfies(l -> assertThat(l.getTarget()).isEqualTo("_vance/workflows/helloworld.yaml"));
    }

    // ──────────── what a waiting run is waiting on ────────────
    //
    // runs-view.md §4.2 lists waitingOnInboxItemId as one of the four shared
    // detail blocks and assigns it to Magrathea ("the gate item"). Nothing
    // filled it, so the detail page could not link to the one thing a
    // reader opens it for.

    @Test
    void detailNamesTheOpenGateItem() {
        stubDetail("r1");
        when(gateAnswers.findOpenGateItem("acme", "r1"))
                .thenReturn(Optional.of(de.mhus.vance.shared.inbox.MaximegalonDocument.builder()
                        .id("item-7")
                        .build()));

        assertThat(source.get("acme", "proj", "r1").orElseThrow().getWaitingOnInboxItemId())
                .isEqualTo("item-7");
    }

    @Test
    void detailOfARunSittingAtNoGateNamesNothing() {
        stubDetail("r1");
        when(gateAnswers.findOpenGateItem("acme", "r1")).thenReturn(Optional.empty());

        assertThat(source.get("acme", "proj", "r1").orElseThrow().getWaitingOnInboxItemId())
                .isNull();
    }

    @Test
    void aFinishedRunIsNotAskedAboutGatesAtAll() {
        // Its gates are closed by definition, and the lookup costs two reads.
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.DONE)));

        assertThat(source.get("acme", "proj", "r1").orElseThrow().getWaitingOnInboxItemId())
                .isNull();
        verify(gateAnswers, never()).findOpenGateItem(any(), any());
    }

    @Test
    void aRunOfAnotherProjectReadsAsAbsent() {
        MagratheaProcessDto foreign = run("r1", MagratheaRunStatus.RUNNING);
        foreign.setProjectId("other");
        when(projector.project(any(), any(), eq("r1"))).thenReturn(Optional.of(foreign));

        assertThat(source.get("acme", "proj", "r1")).isEmpty();
    }

    @Test
    void offersVerbsThatFitTheRunStatus() {
        assertThat(actionsOf(MagratheaRunStatus.RUNNING)).containsExactlyInAnyOrder(RunAction.PAUSE, RunAction.STOP);
        assertThat(actionsOf(MagratheaRunStatus.PAUSED)).containsExactlyInAnyOrder(RunAction.RESUME, RunAction.STOP);
        assertThat(actionsOf(MagratheaRunStatus.DONE)).isEmpty();
        assertThat(actionsOf(MagratheaRunStatus.TERMINATED)).isEmpty();
    }

    @Test
    void performDelegatesToTheWorkflowService() {
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.RUNNING)));

        source.perform("acme", "proj", "r1", RunAction.STOP, "because");

        verify(workflows).stopRun("acme", "proj", "r1", "because");
    }

    @Test
    void aVerbTheStatusDoesNotOfferIsANoOp() {
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", MagratheaRunStatus.DONE)));

        source.perform("acme", "proj", "r1", RunAction.PAUSE, "because");

        verify(workflows, never()).pauseRun(any(), any(), any());
    }

    private java.util.Set<RunAction> actionsOf(MagratheaRunStatus status) {
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", status)));
        return source.allowedActions("acme", "proj", "r1");
    }

    private RunStatus statusOf(MagratheaRunStatus status) {
        when(journal.listRunIds(eq("acme"), eq("proj"), anyInt())).thenReturn(List.of("r1"));
        when(projector.project("acme", "proj", "r1")).thenReturn(Optional.of(run("r1", status)));
        return source.list("acme", "proj", 10).get(0).getStatus();
    }

    private static final Instant JOURNAL_START = Instant.parse("2026-11-04T10:00:00Z");

    /** Feeds the given records to the source as the journal of {@code runId}, oldest first. */
    private void stubJournal(String runId, JournalRecord... records) {
        List<MagratheaJournalEntry> entries = new ArrayList<>();
        for (JournalRecord record : records) {
            // Distinct ids: the entry is a value class, and two equal ones would
            // share one stub.
            MagratheaJournalEntry entry = MagratheaJournalEntry.builder()
                    .id("e" + entries.size())
                    .workflowRunId(runId)
                    .createdAt(JOURNAL_START)
                    .build();
            when(journal.toRecord(entry)).thenReturn(Optional.of(record));
            entries.add(entry);
        }
        when(journal.read("acme", "proj", runId)).thenReturn(entries);
    }

    private void stubDetail(String runId) {
        when(projector.project("acme", "proj", runId)).thenReturn(Optional.of(run(runId, MagratheaRunStatus.RUNNING)));
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
