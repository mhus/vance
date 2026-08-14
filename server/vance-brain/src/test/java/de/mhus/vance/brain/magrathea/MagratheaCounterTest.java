package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.VarRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code enterCounter:} / {@code resetCounters:} — a plan counting its own
 * rounds, written as ordinary variables.
 */
class MagratheaCounterTest {

    private static final String YAML = """
            start: setup
            states:
              setup:
                type: condition_task
                resetCounters: [rounds]
                transitions:
                  - else: writer
              writer:
                type: condition_task
                enterCounter: rounds
                transitions:
                  - if: "#state['rounds'] >= 3"
                    to: giveup
                  - else: writer
              giveup:
                type: terminal
                outcome: failure
            """;

    private final de.mhus.vance.shared.magrathea.MagratheaJournalService journalService =
            mock(de.mhus.vance.shared.magrathea.MagratheaJournalService.class);
    private final MagratheaWorkflowLoader workflowLoader = mock(MagratheaWorkflowLoader.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaProjectLaneManager laneManager = mock(MagratheaProjectLaneManager.class);
    private final MagratheaStateProjector projector = mock(MagratheaStateProjector.class);

    /** Variables as the projector would replay them, updated by captured writes. */
    private final Map<String, Object> vars = new LinkedHashMap<>();
    private final List<JournalRecord> appended = new ArrayList<>();

    private final MagratheaWorkflowService service = new MagratheaWorkflowService(
            workflowLoader,
            mock(de.mhus.vance.shared.document.DocumentService.class),
            journalService,
            taskService,
            laneManager,
            mock(MagratheaTaskExecutor.class),
            mock(MagratheaLocalDispatch.class),
            projector,
            mock(MagratheaOwnerNotifier.class),
            mock(org.springframework.context.ApplicationEventPublisher.class),
            new de.mhus.vance.shared.metric.MetricService(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            mock(de.mhus.vance.shared.thinkprocess.ThinkProcessService.class),
            mock(de.mhus.vance.shared.inbox.InboxItemService.class),
            mock(de.mhus.vance.shared.magrathea.MagratheaTimerService.class));

    @BeforeEach
    void setUp() {
        doAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(laneManager).submitTracked(any(), any());
        when(taskService.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        // Journal writes feed straight back into the projected variables,
        // which is what the real replay does.
        doAnswer(inv -> {
            JournalRecord record = inv.getArgument(3);
            appended.add(record);
            if (record instanceof VarRecord vr) {
                vars.put(vr.getKey(), vr.getValue() == null ? null : vr.getValue().asLong());
            }
            return null;
        }).when(journalService).append(any(), any(), any(), any(JournalRecord.class));

        when(projector.projectVars(any(), any(), any())).thenReturn(vars);

        ResolvedMagratheaWorkflow wf = MagratheaWorkflowLoader.parseYaml("counting", YAML);
        when(workflowLoader.load(any(), any(), any())).thenReturn(Optional.of(wf));
    }

    @Test
    void parse_readsCounterFieldsAsLifecycleNotSpec() {
        ResolvedMagratheaWorkflow wf = MagratheaWorkflowLoader.parseYaml("counting", YAML);

        assertThat(wf.states().get("writer").enterCounter()).isEqualTo("rounds");
        assertThat(wf.states().get("setup").resetCounters()).containsExactly("rounds");
        // Not leaked into the type-specific spec map.
        assertThat(wf.states().get("writer").spec()).doesNotContainKey("enterCounter");
        assertThat(wf.states().get("setup").spec()).doesNotContainKey("resetCounters");
    }

    @Test
    void start_onAStateThatResets_writesZero() {
        service.start("t", "p", "counting", null, "someone");

        assertThat(vars).containsEntry("rounds", 0L);
    }

    @Test
    void enteringACountedState_increments() {
        vars.put("rounds", 2L);

        service.handleCompletion(completionOf("setup", "success"));

        assertThat(vars).containsEntry("rounds", 3L);
    }

    @Test
    void reEnteringASectionThatResets_startsCountingAgain() {
        // The whole point: a back-edge into the section must not inherit the
        // previous pass's count, or the plan gives up after one round.
        vars.put("rounds", 3L);

        service.handleCompletion(completionOf("writer", "back_to_setup"));

        assertThat(vars).containsEntry("rounds", 0L);
    }

    @Test
    void counterOnANonNumericValue_restartsInsteadOfFailing() {
        vars.put("rounds", "not a number");

        service.handleCompletion(completionOf("setup", "success"));

        assertThat(vars).containsEntry("rounds", 1L);
    }

    /** A completion of {@code state} that routes onward to the next state. */
    private TaskCompletedEvent completionOf(String state, String outcome) {
        String target = "setup".equals(state) ? "writer" : "setup";
        when(taskService.findById(any())).thenReturn(Optional.of(
                de.mhus.vance.shared.magrathea.MagratheaTaskDocument.builder()
                        .id("task-x").tenantId("t").projectId("p").workflowRunId("run-1")
                        .stateName(state)
                        .taskType(de.mhus.vance.api.magrathea.MagratheaTaskType.CONDITION_TASK)
                        .build()));
        when(journalService.appendIfAbsent(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(
                        mock(de.mhus.vance.shared.magrathea.MagratheaJournalEntry.class)));
        when(journalService.readLast(any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(
                        de.mhus.vance.shared.magrathea.journal.StartRecord.class)))
                .thenReturn(Optional.of(
                        de.mhus.vance.shared.magrathea.journal.StartRecord.builder()
                                .workflowName("counting")
                                .definitionYaml(YAML)
                                .build()));
        when(workflowLoader.validateYaml(any(), any()))
                .thenReturn(MagratheaWorkflowLoader.parseYaml("counting", YAML));

        return new TaskCompletedEvent(
                "t", "p", "run-1", "task-x", state,
                de.mhus.vance.api.magrathea.MagratheaTaskType.CONDITION_TASK,
                outcome, null, null, 0L, target);
    }
}
