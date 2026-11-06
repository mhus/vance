package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The executor walks a whole loop: it is asked once per entry of the loop state, with the variables the earlier
 * answers set, and the state before it.
 */
class ForEachTaskExecutorTest {

    private static final String YAML = """
            start: loop
            states:
              loop:
                type: for_each_task
                items: "${state.names}"
                as: name
                on: { each: greet, done: end }
              greet:
                type: condition_task
                transitions:
                  - else: loop
              end:
                type: terminal
                outcome: success
            """;

    private final ResolvedMagratheaWorkflow workflow = MagratheaWorkflowLoader.parseYaml("demo", YAML);
    private final ForEachTaskExecutor executor =
            new ForEachTaskExecutor(JsonMapper.builder().build());
    private final tools.jackson.databind.ObjectMapper mapper =
            JsonMapper.builder().build();

    private MagratheaTaskContext ctx(Map<String, Object> items, Map<String, Object> vars, String previous) {
        MagratheaStateSpec loop = workflow.states().get("loop");
        Map<String, Object> spec = new LinkedHashMap<>(loop.spec());
        spec.putAll(items);
        return new MagratheaTaskContext(
                "t",
                "p",
                "run-1",
                "task-1",
                "someone",
                workflow,
                loop.withSpec(spec),
                Map.of(),
                vars,
                null,
                null,
                previous);
    }

    /** What the dispatcher does with an outcome: write its variables, as the replay would read them. */
    private void apply(TaskOutcome outcome, Map<String, Object> vars) {
        outcome.vars().forEach((key, node) -> vars.put(key, mapper.convertValue(node, Object.class)));
    }

    @Test
    void walks_a_list_to_its_end_and_routes_by_the_outcome() {
        Map<String, Object> vars = new LinkedHashMap<>();
        Map<String, Object> items = Map.of("items", List.of("Ann", "Bob"));
        List<String> greeted = new java.util.ArrayList<>();

        String previous = "start-of-run";
        TaskOutcome outcome = executor.execute(ctx(items, vars, previous)).orElseThrow();
        while (outcome.outcome().equals("each")) {
            apply(outcome, vars);
            greeted.add((String) vars.get("name"));
            assertThat(workflow.states().get("loop").onOutcomes().get(outcome.outcome()))
                    .isEqualTo("greet");
            outcome = executor.execute(ctx(items, vars, "greet")).orElseThrow();
        }

        assertThat(greeted).containsExactly("Ann", "Bob");
        assertThat(outcome.outcome()).isEqualTo("done");
        assertThat(workflow.states().get("loop").onOutcomes().get("done")).isEqualTo("end");
        assertThat(vars).containsEntry("index", 1).containsEntry("count", 2);
    }

    @Test
    void a_body_closed_by_an_end_state_goes_on_to_the_next_element_when_the_end_sends_the_run_back() {
        ResolvedMagratheaWorkflow closed = MagratheaWorkflowLoader.parseYaml("demo", """
                start: loop
                states:
                  loop:
                    type: for_each_task
                    items: "${state.names}"
                    as: name
                    on: { each: greet, done: finish }
                  greet:
                    type: condition_task
                    transitions:
                      - else: next
                  next:
                    type: end
                  finish:
                    type: terminal
                    outcome: success
                """);
        MagratheaStateSpec loop = closed.states().get("loop");
        Map<String, Object> items = Map.of("items", List.of("Ann", "Bob"));
        Map<String, Object> vars = new LinkedHashMap<>();
        Map<String, Object> spec = new LinkedHashMap<>(loop.spec());
        spec.putAll(items);
        java.util.function.Function<String, TaskOutcome> enter = previous -> executor.execute(new MagratheaTaskContext(
                        "t",
                        "p",
                        "run-1",
                        "task-1",
                        "someone",
                        closed,
                        loop.withSpec(spec),
                        Map.of(),
                        vars,
                        null,
                        null,
                        previous))
                .orElseThrow();

        TaskOutcome first = enter.apply(null);
        apply(first, vars);
        // The end state sent the run back: the loop is entered from 'next', which is part of its body.
        TaskOutcome second = enter.apply("next");
        apply(second, vars);
        TaskOutcome last = enter.apply("next");

        assertThat(first.outcome()).isEqualTo("each");
        assertThat(second.outcome()).isEqualTo("each");
        assertThat(vars).containsEntry("name", "Bob");
        assertThat(last.outcome()).isEqualTo("done");
    }

    @Test
    void the_variables_go_out_as_json_in_the_order_they_are_to_be_written() {
        TaskOutcome outcome = executor.execute(
                        ctx(Map.of("items", List.of(Map.of("id", 7))), new LinkedHashMap<>(), null))
                .orElseThrow();

        assertThat(outcome.vars().keySet()).startsWith("_loop_loop_items", "_loop_loop_index", "_loop_loop_done");
        JsonNode element = outcome.vars().get("name");
        assertThat(element.get("id").asInt()).isEqualTo(7);
        assertThat(outcome.vars().get("count").asInt()).isEqualTo(1);
    }

    @Test
    void a_loop_that_collects_gives_the_results_as_the_output() {
        Map<String, Object> vars = new LinkedHashMap<>();
        TaskOutcome first = executor.execute(ctx(Map.of("items", List.of("a"), "collect", "ignored"), vars, null))
                .orElseThrow();
        apply(first, vars);

        TaskOutcome last = executor.execute(
                        ctx(Map.of("items", List.of("a"), "collect", Map.of("greeting", "hi a")), vars, "greet"))
                .orElseThrow();

        assertThat(last.outcome()).isEqualTo("done");
        assertThat(last.output().get(0).get("greeting").asText()).isEqualTo("hi a");
    }

    @Test
    void a_state_that_is_no_list_fails_as_a_failure_outcome() {
        Optional<TaskOutcome> outcome = executor.execute(ctx(Map.of("items", "nope"), new LinkedHashMap<>(), null));

        assertThat(outcome.orElseThrow().outcome()).isEqualTo("failure");
        assertThat(outcome.get().errorMessage()).contains("not a list");
        assertThat(outcome.get().vars()).isEmpty();
    }

    @Test
    void is_for_the_for_each_type() {
        assertThat(executor.type()).isEqualTo(MagratheaTaskType.FOR_EACH_TASK);
    }
}
