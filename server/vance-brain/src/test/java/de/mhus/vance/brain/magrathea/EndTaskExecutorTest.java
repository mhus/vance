package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An end sends the run back to the loop whose body it closes. */
class EndTaskExecutorTest {

    private static final String YAML = """
            start: outer
            states:
              outer:
                type: for_each_task
                items: "${state.rows}"
                on: { each: inner, done: finish }
              inner:
                type: for_each_task
                items: "${state.row}"
                on: { each: cell, done: row_end }
              cell:
                type: condition_task
                transitions:
                  - else: cell_end
              cell_end:
                type: end
              row_end:
                type: end
              finish:
                type: terminal
                outcome: success
            """;

    private final ResolvedMagratheaWorkflow workflow = MagratheaWorkflowLoader.parseYaml("demo", YAML);
    private final EndTaskExecutor executor = new EndTaskExecutor();

    private TaskOutcome run(String state) {
        MagratheaTaskContext ctx = new MagratheaTaskContext(
                "t",
                "p",
                "run-1",
                "task-1",
                "someone",
                workflow,
                workflow.states().get(state),
                Map.of(),
                Map.of(),
                null,
                null);
        return executor.execute(ctx).orElseThrow();
    }

    @Test
    void goes_back_to_the_innermost_loop_whose_body_it_closes() {
        TaskOutcome inner = run("cell_end");

        assertThat(inner.outcome()).isEqualTo("success");
        assertThat(inner.nextStateOverride()).isEqualTo("inner");
    }

    @Test
    void an_end_after_the_inner_loop_is_done_closes_the_outer_one() {
        assertThat(run("row_end").nextStateOverride()).isEqualTo("outer");
    }

    @Test
    void is_for_the_end_type() {
        assertThat(executor.type()).isEqualTo(MagratheaTaskType.END);
    }
}
