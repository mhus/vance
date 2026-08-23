package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.RunCapability;
import de.mhus.vance.shared.magrathea.MagratheaRunBinding;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.brain.magrathea.MagratheaWorkflowService.MagratheaWorkflowException;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The start-time capability gate: a plan containing a state this run
 * could never execute is refused before it begins, unless the state has
 * said what to do about it.
 */
class MagratheaCapabilityGateTest {

    /** A gate that wants to be raised in a conversation — needs an owner process. */
    private static final String DEMANDING_YAML = """
            start: ask
            states:
              ask:
                type: gate_task
                inbox:
                  kind: APPROVAL
                  title: ok?
                on:
                  approved: done
              done:
                type: terminal
                outcome: success
            """;

    /** Same plan, but the author declared what happens when it is impossible. */
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

    private final de.mhus.vance.shared.magrathea.MagratheaJournalService journalService =
            mock(de.mhus.vance.shared.magrathea.MagratheaJournalService.class);
    private final MagratheaWorkflowLoader workflowLoader = mock(MagratheaWorkflowLoader.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaProjectLaneManager laneManager = mock(MagratheaProjectLaneManager.class);
    private final MagratheaTaskExecutor taskExecutor = mock(MagratheaTaskExecutor.class);
    private final MagratheaLocalDispatch localDispatch = mock(MagratheaLocalDispatch.class);
    private final de.mhus.vance.shared.document.DocumentService documentService =
            mock(de.mhus.vance.shared.document.DocumentService.class);

    private final MagratheaWorkflowService service = new MagratheaWorkflowService(
            workflowLoader,
            documentService,
            journalService,
            taskService,
            laneManager,
            taskExecutor,
            localDispatch,
            mock(de.mhus.vance.shared.magrathea.MagratheaStateProjector.class),
            mock(MagratheaOwnerNotifier.class),
            mock(org.springframework.context.ApplicationEventPublisher.class),
            new de.mhus.vance.shared.metric.MetricService(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            mock(de.mhus.vance.shared.thinkprocess.ThinkProcessService.class),
            mock(de.mhus.vance.shared.inbox.MaximegalonService.class),
            mock(de.mhus.vance.shared.magrathea.MagratheaTimerService.class));

    @BeforeEach
    void setUp() {
        // Synchronous lane so start() completes inline.
        doAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(laneManager).submitTracked(any(), any());
        when(taskService.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        // A gate whose spec asks for a conversation needs an owner process.
        MagratheaTypeExecutor gateExecutor = new MagratheaTypeExecutor() {
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
                return Optional.empty();
            }
        };
        when(taskExecutor.executorFor(MagratheaTaskType.GATE_TASK)).thenReturn(gateExecutor);
    }

    private void loaderReturns(String yaml) {
        ResolvedMagratheaWorkflow wf = MagratheaWorkflowLoader.parseYaml("demo", yaml);
        when(workflowLoader.load(any(), any(), any())).thenReturn(Optional.of(wf));
    }

    @Test
    void start_headlessRunWithStateNeedingOwnerProcess_isRefused() {
        loaderReturns(DEMANDING_YAML);

        assertThatThrownBy(() -> service.start("t", "p", "demo", null, "someone"))
                .isInstanceOf(MagratheaWorkflowException.class)
                .hasMessageContaining("ask")
                .hasMessageContaining("OWNER_PROCESS");
    }

    @Test
    void start_boundRunWithStateNeedingOwnerProcess_isAllowed() {
        loaderReturns(DEMANDING_YAML);
        MagratheaRunBinding bound = new MagratheaRunBinding(
                "session-1", "process-1", Set.of(RunCapability.OWNER_PROCESS));

        assertThatCode(() -> service.start("t", "p", "demo", null, "someone", null, null, bound))
                .doesNotThrowAnyException();
    }

    @Test
    void start_stateCatchingCapabilityMissing_isAllowedHeadless() {
        // Declaring the catch is the author saying: I know this can be
        // impossible here, and I have somewhere to go when it is.
        loaderReturns(CATCHING_YAML);

        assertThatCode(() -> service.start("t", "p", "demo", null, "someone"))
                .doesNotThrowAnyException();
    }

    @Test
    void start_boundRun_freezesCapabilitiesIntoTheStartRecord() {
        loaderReturns(DEMANDING_YAML);
        MagratheaRunBinding bound = new MagratheaRunBinding(
                "session-1", "process-1", Set.of(RunCapability.OWNER_PROCESS));

        service.start("t", "p", "demo", null, "someone", null, null, bound);

        StartRecord start = capturedStartRecord();
        assertThat(start.getSessionId()).isEqualTo("session-1");
        assertThat(start.getOwnerProcessId()).isEqualTo("process-1");
        assertThat(start.getCapabilities()).containsExactly("OWNER_PROCESS");
    }

    @Test
    void startFromDocument_carriesTheBindingToo() {
        // Addressing a plan by path and running it for somebody are
        // independent choices. When the path variant dropped the binding,
        // the run started fine and then could not report back — the owner
        // it was supposed to answer was simply not in its start record.
        ResolvedMagratheaWorkflow wf =
                MagratheaWorkflowLoader.parseYaml("demo", CATCHING_YAML);
        when(workflowLoader.validateYaml(any(), any())).thenReturn(wf);
        var doc = new de.mhus.vance.shared.document.DocumentDocument();
        doc.setPath("_vance/workflows/demo.yaml");
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.of(doc));
        when(documentService.readContent(any())).thenReturn(CATCHING_YAML);

        MagratheaRunBinding bound = new MagratheaRunBinding(
                "session-1", "process-1", Set.of(RunCapability.OWNER_PROCESS));

        service.startFromDocument("t", "p", "_vance/workflows/demo.yaml",
                null, "someone", bound);

        StartRecord start = capturedStartRecord();
        assertThat(start.getOwnerProcessId()).isEqualTo("process-1");
        assertThat(start.getSessionId()).isEqualTo("session-1");
    }

    @Test
    void start_recordsWhichParamsWereInterpretedRatherThanGiven() {
        // The values are in params either way. What this adds is that one of
        // them was a reading of prose — the first thing worth knowing when a
        // run did something nobody expected.
        loaderReturns(CATCHING_YAML);
        MagratheaRunBinding bound = new MagratheaRunBinding(
                "session-1", "process-1", Set.of(RunCapability.OWNER_PROCESS))
                .withDerivedParams(Set.of("version"));

        service.start("t", "p", "demo", java.util.Map.of("version", "1.0.0"),
                "someone", null, null, bound);

        assertThat(capturedStartRecord().getDerivedParamKeys()).containsExactly("version");
    }

    @Test
    void start_headlessRun_recordsNoBinding() {
        loaderReturns(CATCHING_YAML);

        service.start("t", "p", "demo", null, "someone");

        StartRecord start = capturedStartRecord();
        assertThat(start.getSessionId()).isNull();
        assertThat(start.getOwnerProcessId()).isNull();
        assertThat(start.getCapabilities()).isEmpty();
        assertThat(start.getDerivedParamKeys()).isNull();
    }

    private StartRecord capturedStartRecord() {
        ArgumentCaptor<de.mhus.vance.shared.magrathea.journal.JournalRecord> captor =
                ArgumentCaptor.forClass(
                        de.mhus.vance.shared.magrathea.journal.JournalRecord.class);
        verify(journalService, atLeastOnce())
                .append(any(), any(), any(), captor.capture());
        return captor.getAllValues().stream()
                .filter(StartRecord.class::isInstance)
                .map(StartRecord.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no StartRecord was appended"));
    }
}
