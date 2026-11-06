package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaDebugSetup;
import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaRunBinding;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.BreakpointsRecord;
import de.mhus.vance.shared.magrathea.journal.DebugRecord;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StatusRecord;
import de.mhus.vance.shared.magrathea.journal.TaskResultRecord;
import de.mhus.vance.shared.magrathea.journal.TaskStartedRecord;
import de.mhus.vance.shared.magrathea.journal.VarRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Debug mode: a run started with the debug flag halts before each state and
 * waits to be stepped; a resume ends the halting.
 *
 * <p>The journal is a small fake over the list of appended records, so the
 * service reads back what it wrote — which is what the halting depends on
 * (the status, the start flag, the latest {@link DebugRecord}).
 */
class MagratheaDebugModeTest {

    private static final String YAML = """
            description: debug demo
            start: first
            states:
              first:
                type: condition_task
                transitions:
                  - else: second
              second:
                type: condition_task
                transitions:
                  - else: done
              done:
                type: terminal
                outcome: success
            """;

    /** A workflow with something to simulate: a tool (with a retry block), a gate, two ends. */
    private static final String SIM_YAML = """
            description: simulate demo
            start: call
            states:
              call:
                type: tool_task
                tool: some_tool
                params: {}
                storeAs: res
                retry: { maxAttempts: 3, on: [technical_error] }
                on:
                  success: done
                  partial: ask
                catch:
                  technical_error: failed
              ask:
                type: gate_task
                inbox: { kind: APPROVAL, title: "ok?" }
                on:
                  approved: done
                  rejected: failed
              done:
                type: terminal
                outcome: success
              failed:
                type: terminal
                outcome: failure
            """;

    /** A timer that can be aborted: its {@code catch:} for the aborted kind, and a retry that must not apply. */
    private static final String ABORT_YAML = """
            description: abort demo
            start: wait
            states:
              wait:
                type: timer_task
                duration: "60s"
                retry: { maxAttempts: 3, on: [cancelled] }
                on:
                  fired: done
                catch:
                  cancelled: aborted
              done:
                type: terminal
                outcome: success
              aborted:
                type: terminal
                outcome: failure
            """;

    private final MagratheaJournalService journal = mock(MagratheaJournalService.class);
    private final MagratheaWorkflowLoader loader = mock(MagratheaWorkflowLoader.class);
    private final MagratheaTaskService tasks = mock(MagratheaTaskService.class);
    private final MagratheaProjectLaneManager lanes = mock(MagratheaProjectLaneManager.class);
    private final MagratheaLocalDispatch dispatch = mock(MagratheaLocalDispatch.class);

    private final de.mhus.vance.shared.magrathea.MagratheaTimerService timers =
            mock(de.mhus.vance.shared.magrathea.MagratheaTimerService.class);

    private final MagratheaWorkflowService service = new MagratheaWorkflowService(
            loader,
            mock(de.mhus.vance.shared.document.DocumentService.class),
            journal,
            tasks,
            lanes,
            mock(MagratheaTaskExecutor.class),
            dispatch,
            mock(de.mhus.vance.shared.magrathea.MagratheaStateProjector.class),
            mock(MagratheaOwnerNotifier.class),
            mock(org.springframework.context.ApplicationEventPublisher.class),
            new de.mhus.vance.shared.metric.MetricService(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            mock(de.mhus.vance.shared.thinkprocess.ThinkProcessService.class),
            mock(de.mhus.vance.shared.inbox.MaximegalonService.class),
            timers,
            org.mockito.Mockito.mock(MagratheaRunCost.class));

    private final List<JournalRecord> appended = new ArrayList<>();
    private final List<MagratheaTaskDocument> inserted = new ArrayList<>();

    @BeforeEach
    void wire() {
        doAnswer(inv -> {
                    ((Runnable) inv.getArgument(1)).run();
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                })
                .when(lanes)
                .submitTracked(any(), any());
        doAnswer(inv -> {
                    ((Runnable) inv.getArgument(1)).run();
                    return null;
                })
                .when(lanes)
                .submit(any(), any());

        when(journal.append(any(), any(), any(), any(JournalRecord.class))).thenAnswer(inv -> {
            appended.add(inv.getArgument(3));
            return MagratheaJournalEntry.builder().build();
        });
        when(journal.appendIfAbsent(any(), any(), any(), any(), any(JournalRecord.class)))
                .thenAnswer(inv -> {
                    appended.add(inv.getArgument(4));
                    return Optional.of(MagratheaJournalEntry.builder().build());
                });
        // The fake: the latest appended record of the asked type.
        when(journal.readLast(any(), any(), any(), any(Class.class))).thenAnswer(inv -> {
            Class<?> type = inv.getArgument(3);
            for (int i = appended.size() - 1; i >= 0; i--) {
                if (type.isInstance(appended.get(i))) return Optional.of(appended.get(i));
            }
            return Optional.empty();
        });

        when(tasks.insert(any(MagratheaTaskDocument.class))).thenAnswer(inv -> {
            MagratheaTaskDocument t = inv.getArgument(0);
            t.setId("t" + (inserted.size() + 1));
            inserted.add(t);
            return t;
        });
        when(tasks.findById(any()))
                .thenAnswer(inv -> inserted.stream()
                        .filter(t -> inv.getArgument(0).equals(t.getId()))
                        .findFirst());
        when(tasks.findByRun(any())).thenAnswer(inv -> List.copyOf(inserted));
        // In the database a finished task is no longer held; the fake has to say so too,
        // or a second look for "the held task" finds the one that is long done.
        doAnswer(inv -> {
                    setStatus(inv.getArgument(0), MagratheaTaskStatus.DONE);
                    return null;
                })
                .when(tasks)
                .markDone(any());
        doAnswer(inv -> {
                    setStatus(inv.getArgument(0), MagratheaTaskStatus.FAILED);
                    return null;
                })
                .when(tasks)
                .markFailed(any());
        when(tasks.releaseRun(any())).thenAnswer(inv -> {
            long n = 0;
            for (MagratheaTaskDocument t : inserted) {
                if (t.getStatus() == MagratheaTaskStatus.HELD) {
                    t.setStatus(MagratheaTaskStatus.PENDING);
                    n++;
                }
            }
            return n;
        });

        MagratheaWorkflowLoader real = new MagratheaWorkflowLoader(null);
        when(loader.load(any(), any(), eq("demo"))).thenAnswer(inv -> Optional.of(real.validateYaml("demo", YAML)));
        when(loader.validateYaml(eq("demo"), any())).thenAnswer(inv -> real.validateYaml("demo", YAML));
        when(loader.load(any(), any(), eq("abort")))
                .thenAnswer(inv -> Optional.of(real.validateYaml("abort", ABORT_YAML)));
        when(loader.validateYaml(eq("abort"), any())).thenAnswer(inv -> real.validateYaml("abort", ABORT_YAML));
        when(loader.load(any(), any(), eq("sim"))).thenAnswer(inv -> Optional.of(real.validateYaml("sim", SIM_YAML)));
        when(loader.validateYaml(eq("sim"), any())).thenAnswer(inv -> real.validateYaml("sim", SIM_YAML));
    }

    @Test
    void aDebugRunHaltsBeforeItsFirstState() {
        String runId = startDebug();

        assertThat(appended.get(0))
                .isInstanceOfSatisfying(
                        StartRecord.class, s -> assertThat(s.isDebug()).isTrue());
        assertThat(inserted).singleElement().satisfies(t -> {
            assertThat(t.getStateName()).isEqualTo("first");
            assertThat(t.getStatus()).isEqualTo(MagratheaTaskStatus.HELD);
        });
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.PAUSED);
        assertThat(lastStatus().getReason()).contains("before state 'first'");
    }

    @Test
    void aNormalRunIsNotHalted() {
        service.start("acme", "proj", "demo", Map.of(), "alice");

        assertThat(inserted)
                .singleElement()
                .satisfies(t -> assertThat(t.getStatus()).isEqualTo(MagratheaTaskStatus.PENDING));
        assertThat(appended).noneMatch(r -> r instanceof StatusRecord);
    }

    @Test
    void stepReleasesTheStateAndHaltsAgainBeforeTheNext() {
        String runId = startDebug();

        assertThat(service.stepRun("acme", "proj", runId)).isTrue();

        MagratheaTaskDocument first = inserted.get(0);
        assertThat(first.getStatus()).isEqualTo(MagratheaTaskStatus.PENDING);
        // Released tasks go to the lane at once, not at the claimer's next scan.
        verify(dispatch, atLeastOnce()).dispatch(first);
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.RUNNING);

        service.handleCompletion(completed(runId, "t1", "first", "second"));

        assertThat(inserted.get(1).getStateName()).isEqualTo("second");
        assertThat(inserted.get(1).getStatus()).isEqualTo(MagratheaTaskStatus.HELD);
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.PAUSED);
        assertThat(lastStatus().getReason()).contains("before state 'second'");
    }

    @Test
    void resumeEndsTheHaltingSoTheRunGoesOn() {
        String runId = startDebug();

        assertThat(service.resumeRun("acme", "proj", runId)).isTrue();

        // The stepping is switched off before the queue is released, so no
        // completion can halt the run again in between.
        var order = inOrder(journal, tasks);
        order.verify(journal).append(any(), any(), any(), any(DebugRecord.class));
        order.verify(tasks).releaseRun(runId);
        assertThat(appended)
                .anySatisfy(r -> assertThat(r)
                        .isInstanceOfSatisfying(
                                DebugRecord.class,
                                d -> assertThat(d.isStepping()).isFalse()));

        service.handleCompletion(completed(runId, "t1", "first", "second"));

        assertThat(inserted.get(1).getStatus()).isEqualTo(MagratheaTaskStatus.PENDING);
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.RUNNING);
    }

    @Test
    void aStepAfterAResumePutsTheHaltingBack() {
        String runId = startDebug();
        service.resumeRun("acme", "proj", runId);
        service.pauseRun("acme", "proj", runId);

        assertThat(service.stepRun("acme", "proj", runId)).isTrue();

        assertThat(appended.stream().filter(r -> r instanceof DebugRecord).reduce((a, b) -> b))
                .get()
                .isInstanceOfSatisfying(
                        DebugRecord.class, d -> assertThat(d.isStepping()).isTrue());
    }

    @Test
    void aRunThatIsNotHaltedCannotBeStepped() {
        String runId = startDebug();
        service.stepRun("acme", "proj", runId);

        // RUNNING now: the button came from an older snapshot.
        assertThat(service.stepRun("acme", "proj", runId)).isFalse();
    }

    @Test
    void aNormalPausedRunCannotBeStepped() {
        String runId = service.start("acme", "proj", "demo", Map.of(), "alice");
        service.pauseRun("acme", "proj", runId);

        assertThat(service.stepRun("acme", "proj", runId)).isFalse();
        assertThat(appended).noneMatch(r -> r instanceof DebugRecord);
    }

    // ──────────────────── breakpoints ────────────────────

    @Test
    void aRunWithABreakpointRunsFreelyUntilThere() {
        String runId = startWithBreakpoints("second");

        // Not halted at the start: nothing says so for the first state.
        assertThat(inserted.get(0).getStatus()).isEqualTo(MagratheaTaskStatus.PENDING);
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.RUNNING);

        service.handleCompletion(completed(runId, "t1", "first", "second"));

        assertThat(inserted.get(1).getStateName()).isEqualTo("second");
        assertThat(inserted.get(1).getStatus()).isEqualTo(MagratheaTaskStatus.HELD);
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.PAUSED);
        assertThat(lastStatus().getReason()).contains("breakpoint: before state 'second'");
    }

    @Test
    void aBreakpointOnTheFirstStateHaltsAtTheStart() {
        String runId = startWithBreakpoints("first");

        assertThat(inserted.get(0).getStatus()).isEqualTo(MagratheaTaskStatus.HELD);
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.PAUSED);
        assertThat(lastStatus().getReason()).contains("breakpoint: before state 'first'");
    }

    @Test
    void resumingAtABreakpointRunsOnUntilTheNextOne() {
        String runId = startWithBreakpoints("second", "done");
        service.handleCompletion(completed(runId, "t1", "first", "second"));
        assertThat(service.resumeRun("acme", "proj", runId)).isTrue();
        assertThat(inserted.get(1).getStatus()).isEqualTo(MagratheaTaskStatus.PENDING);

        service.handleCompletion(completed(runId, "t2", "second", "done"));

        // The resume ended any stepping, but the breakpoints stay.
        assertThat(inserted.get(2).getStateName()).isEqualTo("done");
        assertThat(inserted.get(2).getStatus()).isEqualTo(MagratheaTaskStatus.HELD);
        assertThat(lastStatus().getReason()).contains("breakpoint: before state 'done'");
    }

    @Test
    void steppingFromABreakpointHaltsBeforeTheNextStateWhateverItIs() {
        String runId = startWithBreakpoints("second");
        service.handleCompletion(completed(runId, "t1", "first", "second"));
        assertThat(service.stepRun("acme", "proj", runId)).isTrue();

        service.handleCompletion(completed(runId, "t2", "second", "done"));

        assertThat(inserted.get(2).getStatus()).isEqualTo(MagratheaTaskStatus.HELD);
        assertThat(lastStatus().getReason()).contains("debug: before state 'done'");
    }

    @Test
    void changingTheBreakpointsMovesWhereTheRunHalts() {
        String runId = startWithBreakpoints("done");

        assertThat(service.setBreakpoints("acme", "proj", runId, java.util.List.of("second")))
                .isTrue();
        service.handleCompletion(completed(runId, "t1", "first", "second"));

        assertThat(inserted.get(1).getStatus()).isEqualTo(MagratheaTaskStatus.HELD);
        assertThat(appended)
                .anySatisfy(r -> assertThat(r)
                        .isInstanceOfSatisfying(
                                BreakpointsRecord.class,
                                b -> assertThat(b.getBreakpoints()).containsExactly("second")));
    }

    @Test
    void clearingTheBreakpointsLetsTheRunGoThrough() {
        String runId = startWithBreakpoints("second");

        assertThat(service.setBreakpoints("acme", "proj", runId, java.util.List.of()))
                .isTrue();
        service.handleCompletion(completed(runId, "t1", "first", "second"));

        assertThat(inserted.get(1).getStatus()).isEqualTo(MagratheaTaskStatus.PENDING);
    }

    @Test
    void aBreakpointOnAStateTheWorkflowDoesNotHaveIsRefused() {
        String runId = startWithBreakpoints("second");
        int before = appended.size();

        assertThatThrownBy(() -> service.setBreakpoints("acme", "proj", runId, java.util.List.of("nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown state 'nope'");
        assertThat(appended).hasSize(before);
    }

    @Test
    void startingWithAnUnknownBreakpointStartsNothing() {
        assertThatThrownBy(() -> service.start(
                        "acme",
                        "proj",
                        "demo",
                        Map.of(),
                        "alice",
                        null,
                        null,
                        MagratheaRunBinding.headless(),
                        new MagratheaDebugSetup(false, java.util.Set.of("nope"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown state 'nope'");
        assertThat(appended).isEmpty();
        assertThat(inserted).isEmpty();
    }

    @Test
    void anOrdinaryRunHasNoBreakpointsToSet() {
        String runId = service.start("acme", "proj", "demo", Map.of(), "alice");

        assertThat(service.setBreakpoints("acme", "proj", runId, java.util.List.of("second")))
                .isFalse();
        assertThat(appended).noneMatch(r -> r instanceof BreakpointsRecord);
    }

    @Test
    void breakpointsCannotBeSetOnARunThatHasEnded() {
        String runId = startWithBreakpoints("done");
        service.stopRun("acme", "proj", runId, "why");

        assertThat(service.setBreakpoints("acme", "proj", runId, java.util.List.of("second")))
                .isFalse();
    }

    @Test
    void anOrdinaryRunPaysNothingOnItsTransitions() {
        // Looking for halts is for debuggable runs only; an ordinary transition
        // must not read the debug records at all.
        String runId = service.start("acme", "proj", "demo", Map.of(), "alice");

        service.handleCompletion(completed(runId, "t1", "first", "second"));

        verify(journal, never()).readLast(any(), any(), any(), eq(DebugRecord.class));
        verify(journal, never()).readLast(any(), any(), any(), eq(BreakpointsRecord.class));
    }

    // ──────────────────── simulate ────────────────────

    @Test
    void aHaltedRunOffersTheOutcomesTheStateHasAWayOnFor() {
        String runId = startSim();

        var offered = service.simulationFor("acme", "proj", runId).orElseThrow();

        assertThat(offered.state()).isEqualTo("call");
        assertThat(offered.taskType()).isEqualTo(MagratheaTaskType.TOOL_TASK);
        // The on: keys first, then the error kinds of catch:.
        assertThat(offered.outcomes()).containsExactly("success", "partial", "technical_error");
    }

    @Test
    void aConditionStateHasNothingToSimulate() {
        // A condition is pure: evaluating it is the whole point, there is no effect to replace.
        String runId = startDebug();

        assertThat(service.simulationFor("acme", "proj", runId)).isEmpty();
        assertThat(service.simulateRun("acme", "proj", runId, "success", null, null))
                .isFalse();
    }

    @Test
    void aRunThatIsNotHaltedOffersNothing() {
        String runId = startSim();
        service.stepRun("acme", "proj", runId);

        assertThat(service.simulationFor("acme", "proj", runId)).isEmpty();
    }

    @Test
    void anOrdinaryRunOffersNothing() {
        String runId = service.start("acme", "proj", "sim", Map.of(), "alice");

        assertThat(service.simulationFor("acme", "proj", runId)).isEmpty();
        assertThat(service.simulateRun("acme", "proj", runId, "success", null, null))
                .isFalse();
    }

    @Test
    void simulatingGivesTheResultInsteadOfRunningTheTask() {
        String runId = startSim();
        var output = new tools.jackson.databind.ObjectMapper().valueToTree(Map.of("ticket", "ABC-1"));

        assertThat(service.simulateRun("acme", "proj", runId, "success", output, null))
                .isTrue();

        // The task was never released: it did not run.
        verify(tasks, never()).releaseRun(any());
        // Its result is journalled as that of the task, marked simulated, with the given output …
        assertThat(appended)
                .anySatisfy(r -> assertThat(r).isInstanceOfSatisfying(TaskResultRecord.class, t -> {
                    assertThat(t.getState()).isEqualTo("call");
                    assertThat(t.getOutcome()).isEqualTo("success");
                    assertThat(t.isSimulated()).isTrue();
                    assertThat(t.getOutput().get("ticket").asString()).isEqualTo("ABC-1");
                }));
        // … and the task has a start record, so the step shows its type.
        assertThat(appended)
                .anySatisfy(r -> assertThat(r)
                        .isInstanceOfSatisfying(
                                TaskStartedRecord.class,
                                t -> assertThat(t.getTaskType()).isEqualTo(MagratheaTaskType.TOOL_TASK)));
        // storeAs: reads the simulated output like any other.
        assertThat(appended)
                .anySatisfy(r -> assertThat(r)
                        .isInstanceOfSatisfying(
                                VarRecord.class, v -> assertThat(v.getKey()).isEqualTo("res")));
        verify(tasks).markDone("t1");
    }

    @Test
    void afterSimulatingTheRunHaltsBeforeTheNextStateLikeAfterAStep() {
        String runId = startSim();

        service.simulateRun("acme", "proj", runId, "success", null, null);

        assertThat(inserted.get(1).getStateName()).isEqualTo("done");
        assertThat(inserted.get(1).getStatus()).isEqualTo(MagratheaTaskStatus.HELD);
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.PAUSED);
        assertThat(lastStatus().getReason()).contains("debug: before state 'done'");
    }

    @Test
    void theSimulatedOutcomeChoosesTheWayThroughThePlan() {
        String runId = startSim();

        service.simulateRun("acme", "proj", runId, "partial", null, null);

        assertThat(inserted.get(1).getStateName()).isEqualTo("ask");
    }

    @Test
    void anOutcomeThePlanHasNoWayOnForIsRefusedAndNothingIsWritten() {
        String runId = startSim();
        int before = appended.size();

        assertThatThrownBy(() -> service.simulateRun("acme", "proj", runId, "banana", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Outcome 'banana' leads nowhere")
                .hasMessageContaining("success");
        assertThat(appended).hasSize(before);
        assertThat(inserted).hasSize(1);
    }

    @Test
    void aSimulatedFailureIsNotRetried() {
        // call has a retry block for technical_error. A retry would run the task for real.
        String runId = startSim();

        service.simulateRun("acme", "proj", runId, "technical_error", null, "HTTP 429");

        assertThat(inserted).extracting(MagratheaTaskDocument::getStateName).containsExactly("call", "failed");
        assertThat(appended)
                .anySatisfy(r -> assertThat(r).isInstanceOfSatisfying(TaskResultRecord.class, t -> {
                    assertThat(t.getErrorMessage()).isEqualTo("HTTP 429");
                    assertThat(t.isSimulated()).isTrue();
                }));
    }

    @Test
    void simulatingTwiceInARowDoesNotGiveASecondResult() {
        String runId = startSim();
        assertThat(service.simulateRun("acme", "proj", runId, "success", null, null))
                .isTrue();

        // The run now stands in front of "done" (a terminal): nothing to simulate there.
        assertThat(service.simulateRun("acme", "proj", runId, "success", null, null))
                .isFalse();
    }

    // ──────────────────── helpers ────────────────────

    private void setStatus(String taskId, MagratheaTaskStatus status) {
        inserted.stream().filter(t -> taskId.equals(t.getId())).forEach(t -> t.setStatus(status));
    }

    private String startDebug() {
        return service.start(
                "acme",
                "proj",
                "demo",
                Map.of(),
                "alice",
                /* parent */ null, /* parentState */
                null,
                MagratheaRunBinding.headless(),
                new MagratheaDebugSetup(true, java.util.Set.of()));
    }

    @Test
    void abortingATimerConsumesItAndTheRunTakesItsCancelledCatchWithoutRetrying() {
        String runId = service.start("acme", "proj", "abort", Map.of(), "alice");
        inserted.get(0).setStatus(MagratheaTaskStatus.CLAIMED);
        when(timers.findByLinkedTaskId("t1"))
                .thenReturn(Optional.of(de.mhus.vance.shared.magrathea.MagratheaTimerDocument.builder()
                        .id("tm1")
                        .build()));

        assertThat(service.abortTask("acme", "proj", runId, "aborted from the run view"))
                .isTrue();

        verify(timers).claimFire(eq("tm1"), any());
        // The retry block lists the kind, and still the run goes to the catch.
        assertThat(inserted).hasSize(2);
        assertThat(inserted.get(1).getStateName()).isEqualTo("aborted");
        assertThat(appended)
                .anySatisfy(r -> assertThat(r)
                        .isInstanceOfSatisfying(
                                de.mhus.vance.shared.magrathea.journal.TaskResultRecord.class,
                                t -> assertThat(t.getOutcome()).isEqualTo("cancelled")));
    }

    @Test
    void aRunWithoutAnEndableTaskHasNothingToAbort() {
        String runId = service.start("acme", "proj", "demo", Map.of(), "alice");
        inserted.get(0).setStatus(MagratheaTaskStatus.CLAIMED);

        assertThat(service.abortableTask("acme", "proj", runId)).isEmpty();
        assertThat(service.abortTask("acme", "proj", runId, "why")).isFalse();
        assertThat(inserted).hasSize(1);
    }

    @Test
    void aPlainPausedRunCanSkipItsHeldTaskAndStaysPaused() {
        String runId = service.start("acme", "proj", "sim", Map.of(), "alice");
        service.pauseRun("acme", "proj", runId);
        // The fake does not hold the queue; stand in for the real holdRun.
        inserted.get(0).setStatus(MagratheaTaskStatus.HELD);

        assertThat(service.simulationFor("acme", "proj", runId)).isPresent();
        assertThat(service.simulateRun("acme", "proj", runId, "success", null, null))
                .isTrue();

        // Not a debug run: nothing resumes it, the next task is held by the status.
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.PAUSED);
        assertThat(inserted.get(1).getStateName()).isEqualTo("done");
        assertThat(inserted.get(1).getStatus()).isEqualTo(MagratheaTaskStatus.HELD);
    }

    private String startSim() {
        return service.start(
                "acme",
                "proj",
                "sim",
                Map.of(),
                "alice",
                null,
                null,
                MagratheaRunBinding.headless(),
                new MagratheaDebugSetup(true, java.util.Set.of()));
    }

    private String startWithBreakpoints(String... states) {
        return service.start(
                "acme",
                "proj",
                "demo",
                Map.of(),
                "alice",
                null,
                null,
                MagratheaRunBinding.headless(),
                new MagratheaDebugSetup(false, new java.util.LinkedHashSet<>(java.util.List.of(states))));
    }

    private StatusRecord lastStatus() {
        return appended.stream()
                .filter(r -> r instanceof StatusRecord)
                .map(r -> (StatusRecord) r)
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    private static TaskCompletedEvent completed(String runId, String taskId, String state, String next) {
        return new TaskCompletedEvent(
                "acme",
                "proj",
                runId,
                taskId,
                state,
                MagratheaTaskType.CONDITION_TASK,
                TaskCompletedEvent.OUTCOME_SUCCESS,
                null,
                null,
                5L,
                next);
    }
}
