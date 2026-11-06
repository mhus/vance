package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StateEnteredRecord;
import de.mhus.vance.shared.magrathea.journal.VarRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@code handleCompletion} does with a loop state's answer: the variables it carries are written before the
 * way on is resolved, so the body's first task already sees its element.
 */
class MagratheaLoopStateTest {

    private static final String YAML = """
            start: loop
            states:
              loop:
                type: for_each_task
                items: "${state.names}"
                as: name
                storeAs: greeted
                on: { each: greet, done: end }
              greet:
                type: shell_task
                run: "echo hi"
                on: { success: loop }
              end:
                type: terminal
                outcome: success
            """;

    private final de.mhus.vance.shared.magrathea.MagratheaJournalService journalService =
            mock(de.mhus.vance.shared.magrathea.MagratheaJournalService.class);
    private final MagratheaWorkflowLoader workflowLoader = mock(MagratheaWorkflowLoader.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaProjectLaneManager laneManager = mock(MagratheaProjectLaneManager.class);
    private final MagratheaStateProjector projector = mock(MagratheaStateProjector.class);
    private final tools.jackson.databind.ObjectMapper mapper =
            JsonMapper.builder().build();

    private final Map<String, JsonNode> vars = new LinkedHashMap<>();
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
            mock(de.mhus.vance.shared.inbox.MaximegalonService.class),
            mock(de.mhus.vance.shared.magrathea.MagratheaTimerService.class),
            mock(MagratheaRunCost.class));

    @BeforeEach
    void setUp() {
        doAnswer(inv -> {
                    Runnable r = inv.getArgument(1);
                    r.run();
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                })
                .when(laneManager)
                .submitTracked(any(), any());
        when(taskService.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
                    JournalRecord record = inv.getArgument(3);
                    appended.add(record);
                    if (record instanceof VarRecord vr) vars.put(vr.getKey(), vr.getValue());
                    return null;
                })
                .when(journalService)
                .append(any(), any(), any(), any(JournalRecord.class));
        when(projector.projectVars(any(), any(), any())).thenAnswer(inv -> new LinkedHashMap<String, Object>());
        ResolvedMagratheaWorkflow wf = MagratheaWorkflowLoader.parseYaml("greeting", YAML);
        when(workflowLoader.load(any(), any(), any())).thenReturn(Optional.of(wf));
        when(workflowLoader.validateYaml(any(), any())).thenReturn(wf);
        when(taskService.findById(any()))
                .thenReturn(Optional.of(MagratheaTaskDocument.builder()
                        .id("task-x")
                        .tenantId("t")
                        .projectId("p")
                        .workflowRunId("run-1")
                        .stateName("loop")
                        .taskType(MagratheaTaskType.FOR_EACH_TASK)
                        .build()));
        when(journalService.appendIfAbsent(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(mock(MagratheaJournalEntry.class)));
        when(journalService.readLast(any(), any(), any(), org.mockito.ArgumentMatchers.eq(StartRecord.class)))
                .thenReturn(Optional.of(StartRecord.builder()
                        .workflowName("greeting")
                        .definitionYaml(YAML)
                        .build()));
    }

    private TaskCompletedEvent completion(String outcome, JsonNode output, Map<String, JsonNode> setVars) {
        return new TaskCompletedEvent(
                "t",
                "p",
                "run-1",
                "task-x",
                "loop",
                MagratheaTaskType.FOR_EACH_TASK,
                outcome,
                output,
                null,
                0L,
                null,
                false,
                setVars);
    }

    @Test
    void each_writes_the_element_variables_and_enters_the_body() {
        Map<String, JsonNode> set = new LinkedHashMap<>();
        set.put("name", mapper.valueToTree("Ann"));
        set.put("index", mapper.valueToTree(0));
        set.put("count", mapper.valueToTree(2));

        service.handleCompletion(completion("each", null, set));

        assertThat(vars.get("name").asString()).isEqualTo("Ann");
        assertThat(vars.get("count").asInt()).isEqualTo(2);
        assertThat(appended)
                .filteredOn(StateEnteredRecord.class::isInstance)
                .extracting(r -> ((StateEnteredRecord) r).getState())
                .containsExactly("greet");
        ArgumentCaptor<MagratheaTaskDocument> task = ArgumentCaptor.forClass(MagratheaTaskDocument.class);
        org.mockito.Mockito.verify(taskService).insert(task.capture());
        assertThat(task.getValue().getStateName()).isEqualTo("greet");
    }

    @Test
    void the_variables_are_written_before_the_next_state_is_entered() {
        Map<String, JsonNode> set = new LinkedHashMap<>();
        set.put("name", mapper.valueToTree("Ann"));

        service.handleCompletion(completion("each", null, set));

        int var = indexOf(VarRecord.class);
        int entered = indexOf(StateEnteredRecord.class);
        assertThat(var).isGreaterThanOrEqualTo(0).isLessThan(entered);
    }

    @Test
    void done_stores_the_collected_results_and_leaves_through_the_done_arrow() {
        service.handleCompletion(completion("done", mapper.valueToTree(List.of("r1", "r2")), Map.of()));

        assertThat(vars.get("greeted").get(1).asString()).isEqualTo("r2");
        assertThat(appended)
                .filteredOn(StateEnteredRecord.class::isInstance)
                .extracting(r -> ((StateEnteredRecord) r).getState())
                .containsExactly("end");
    }

    private int indexOf(Class<? extends JournalRecord> type) {
        for (int i = 0; i < appended.size(); i++) {
            if (type.isInstance(appended.get(i))) return i;
        }
        return -1;
    }
}
