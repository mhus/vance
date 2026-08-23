package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.RunCapability;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The runtime half of the capability contract.
 *
 * <p>The start refuses a plan whose states this run could not execute —
 * except the ones declaring {@code catch: { capability_missing: … }}, which
 * is the author opting out of that refusal. Until this existed the waiver
 * only switched the start check off: the exempted state ran as though the
 * capability were there, and the {@code catch:} branch it named could never
 * be reached. This is where the outcome it routes on is produced.
 */
class MagratheaCapabilityOutcomeTest {

    private static final String CATCHING_YAML = """
            start: ask
            states:
              ask:
                type: gate_task
                inbox:
                  kind: APPROVAL
                  title: ok?
                on:
                  approved: done
                catch:
                  capability_missing: done
              done:
                type: terminal
                outcome: success
            """;

    private final MagratheaJournalService journalService = mock(MagratheaJournalService.class);
    private final MagratheaStateProjector projector = mock(MagratheaStateProjector.class);
    private final MagratheaWorkflowLoader workflowLoader = mock(MagratheaWorkflowLoader.class);
    private final MagratheaCompletionEventBus eventBus = mock(MagratheaCompletionEventBus.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<de.mhus.vance.brain.progress.ProgressEmitter> progress =
            mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<de.mhus.vance.shared.thinkprocess.ThinkProcessService> processes =
            mock(ObjectProvider.class);

    /** Records whether it ran; needs an owner process to do its job. */
    private final RecordingGateExecutor gateExecutor = new RecordingGateExecutor();

    private final MagratheaTaskExecutor executor = new MagratheaTaskExecutor(
            journalService, projector, workflowLoader, eventBus, taskService,
            List.of(gateExecutor), progress, processes);

    @BeforeEach
    void setUp() {
        ResolvedMagratheaWorkflow workflow =
                MagratheaWorkflowLoader.parseYaml("demo", CATCHING_YAML);
        when(workflowLoader.validateYaml(any(), any())).thenReturn(workflow);
        when(projector.projectVars(any(), any(), any())).thenReturn(Map.of());
    }

    @Test
    void headlessRun_stateNeedingAnOwnerProcess_producesTheCapabilityMissingOutcome() {
        givenRunStartedWith(Set.of());

        executor.execute(task());

        assertThat(gateExecutor.ran).isFalse();
        assertThat(publishedOutcome())
                .isEqualTo(TaskCompletedEvent.OUTCOME_CAPABILITY_MISSING);
    }

    @Test
    void headlessRun_theOutcomeNamesTheMissingCapability() {
        givenRunStartedWith(Set.of());

        executor.execute(task());

        ArgumentCaptor<TaskCompletedEvent> captor =
                ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(eventBus).publish(captor.capture());
        assertThat(captor.getValue().errorMessage())
                .contains("ask")
                .contains("OWNER_PROCESS");
    }

    @Test
    void boundRun_theStateRunsAsBefore() {
        givenRunStartedWith(Set.of(RunCapability.OWNER_PROCESS.name()));

        executor.execute(task());

        assertThat(gateExecutor.ran).isTrue();
        assertThat(publishedOutcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
    }

    // ── helpers ────────────────────────────────────────────────────

    private void givenRunStartedWith(Set<String> capabilities) {
        StartRecord start = StartRecord.builder()
                .workflowName("demo")
                .definitionYaml(CATCHING_YAML)
                .startedBy("someone")
                .capabilities(capabilities)
                .build();
        when(journalService.readLast(eq("t"), eq("p"), eq("run-1"), eq(StartRecord.class)))
                .thenReturn(Optional.of(start));
    }

    private String publishedOutcome() {
        ArgumentCaptor<TaskCompletedEvent> captor =
                ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(eventBus).publish(captor.capture());
        return captor.getValue().outcome();
    }

    private static MagratheaTaskDocument task() {
        return MagratheaTaskDocument.builder()
                .id("task-1")
                .tenantId("t")
                .projectId("p")
                .workflowRunId("run-1")
                .workflowName("demo")
                .stateName("ask")
                .taskType(MagratheaTaskType.GATE_TASK)
                .status(MagratheaTaskStatus.CLAIMED)
                .build();
    }

    private static final class RecordingGateExecutor implements MagratheaTypeExecutor {
        private boolean ran;

        @Override
        public MagratheaTaskType type() {
            return MagratheaTaskType.GATE_TASK;
        }

        @Override
        public Set<RunCapability> requires(MagratheaStateSpec state) {
            return Set.of(RunCapability.OWNER_PROCESS);
        }

        @Override
        public Optional<TaskOutcome> execute(MagratheaTaskContext context) {
            ran = true;
            return Optional.of(TaskOutcome.success());
        }
    }
}
