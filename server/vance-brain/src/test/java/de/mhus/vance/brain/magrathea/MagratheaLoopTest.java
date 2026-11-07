package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The decision of a loop state: new loop or next element, and what it sets. */
class MagratheaLoopTest {

    private static final Set<String> BODY = Set.of("work", "check");

    private static Map<String, Object> spec(Object... kv) {
        Map<String, Object> spec = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) spec.put((String) kv[i], kv[i + 1]);
        return spec;
    }

    /** Apply a step the way the dispatcher does and keep the run's variables. */
    private static MagratheaLoop.Step run(Map<String, Object> vars, Map<String, Object> spec, String previous) {
        MagratheaLoop.Step step = MagratheaLoop.step("loop", spec, vars, previous, BODY, null);
        vars.putAll(step.vars());
        return step;
    }

    @Test
    void a_new_loop_starts_at_the_first_element_and_sets_element_index_and_count() {
        Map<String, Object> vars = new LinkedHashMap<>();

        MagratheaLoop.Step step = run(vars, spec("items", List.of("a", "b", "c"), "as", "msg"), "prepare");

        assertThat(step.outcome()).isEqualTo("each");
        assertThat(vars).containsEntry("msg", "a").containsEntry("index", 0).containsEntry("count", 3);
        assertThat(vars.get("_loop_loop_items")).isEqualTo(List.of("a", "b", "c"));
    }

    @Test
    void the_element_is_called_item_when_the_state_names_none() {
        Map<String, Object> vars = new LinkedHashMap<>();

        run(vars, spec("items", List.of("a")), null);

        assertThat(vars).containsEntry("item", "a");
    }

    @Test
    void coming_back_from_the_body_goes_on_to_the_next_element_and_after_the_last_it_is_done() {
        Map<String, Object> vars = new LinkedHashMap<>();
        Map<String, Object> spec = spec("items", List.of("a", "b", "c"));
        List<String> seen = new ArrayList<>();

        MagratheaLoop.Step step = run(vars, spec, "prepare");
        while (step.outcome().equals("each")) {
            seen.add((String) vars.get("item"));
            step = run(vars, spec, "check");
        }

        assertThat(seen).containsExactly("a", "b", "c");
        assertThat(step.outcome()).isEqualTo("done");
        assertThat(step.output()).isNull();
        assertThat(vars).containsEntry("_loop_loop_done", true);
    }

    @Test
    void an_empty_list_is_done_at_once() {
        Map<String, Object> vars = new LinkedHashMap<>();

        MagratheaLoop.Step step = run(vars, spec("items", List.of()), "prepare");

        assertThat(step.outcome()).isEqualTo("done");
        assertThat(vars).doesNotContainKey("item");
    }

    @Test
    void a_loop_that_collects_hands_back_one_result_per_element_in_order() {
        Map<String, Object> vars = new LinkedHashMap<>();
        MagratheaLoop.Step step = run(vars, spec("items", List.of("a", "b", "c"), "collect", "x"), "prepare");

        // Each pass of the body ends with its own value; the dispatcher has resolved 'collect' by then.
        step = run(vars, spec("items", List.of("a", "b", "c"), "collect", "r-a"), "check");
        step = run(vars, spec("items", List.of("a", "b", "c"), "collect", "r-b"), "check");
        step = run(vars, spec("items", List.of("a", "b", "c"), "collect", "r-c"), "check");

        assertThat(step.outcome()).isEqualTo("done");
        assertThat(step.output()).isEqualTo(List.of("r-a", "r-b", "r-c"));
    }

    @Test
    void a_loop_without_collect_keeps_no_results_and_an_empty_one_with_collect_gives_an_empty_list() {
        Map<String, Object> vars = new LinkedHashMap<>();
        assertThat(run(vars, spec("items", List.of()), null).output()).isNull();
        assertThat(run(new LinkedHashMap<>(), spec("items", List.of(), "collect", "x"), null)
                        .output())
                .isEqualTo(List.of());
    }

    @Test
    void entered_from_outside_the_body_it_starts_over_even_in_the_middle_of_a_loop() {
        Map<String, Object> vars = new LinkedHashMap<>();
        Map<String, Object> spec = spec("items", List.of("a", "b", "c"));
        run(vars, spec, "prepare");
        run(vars, spec, "check"); // now at element 1 ('b')
        assertThat(vars).containsEntry("index", 1);

        // The body was left by an arrow to somewhere else, and the run comes back later from there.
        MagratheaLoop.Step step = run(vars, spec, "elsewhere");

        assertThat(step.outcome()).isEqualTo("each");
        assertThat(vars).containsEntry("index", 0).containsEntry("item", "a");
    }

    @Test
    void a_loop_that_finished_starts_over_when_it_is_entered_again() {
        Map<String, Object> vars = new LinkedHashMap<>();
        Map<String, Object> spec = spec("items", List.of("a"));
        run(vars, spec, "prepare");
        assertThat(run(vars, spec, "check").outcome()).isEqualTo("done");

        // A later pass reaches the loop through the body's states again: the old loop is over.
        MagratheaLoop.Step step = run(vars, spec, "check");

        assertThat(step.outcome()).isEqualTo("each");
        assertThat(vars).containsEntry("item", "a").containsEntry("_loop_loop_done", false);
    }

    @Test
    void two_loops_keep_their_places_apart() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.putAll(MagratheaLoop.step("a", spec("items", List.of(1, 2)), vars, null, Set.of("x"), null)
                .vars());
        vars.putAll(MagratheaLoop.step("b", spec("items", List.of(9)), vars, null, Set.of("y"), null)
                .vars());

        assertThat(vars).containsKeys("_loop_a_items", "_loop_b_items");
        assertThat(vars.get("_loop_a_index")).isEqualTo(0);
    }

    @Test
    void something_that_is_no_list_fails_the_state() {
        MagratheaLoop.Step none = MagratheaLoop.step("loop", spec(), new LinkedHashMap<>(), null, BODY, null);
        MagratheaLoop.Step text =
                MagratheaLoop.step("loop", spec("items", "a,b"), new LinkedHashMap<>(), null, BODY, null);

        assertThat(none.outcome()).isEqualTo("failure");
        assertThat(none.failure()).contains("not a list").contains("nothing");
        assertThat(text.failure()).contains("not a list").contains("String");
    }

    @Test
    void more_elements_than_allowed_fail_without_starting_a_body() {
        List<Integer> many = java.util.stream.IntStream.range(0, 101).boxed().toList();

        MagratheaLoop.Step defaulted =
                MagratheaLoop.step("loop", spec("items", many), new LinkedHashMap<>(), null, BODY, null);
        MagratheaLoop.Step raised = MagratheaLoop.step(
                "loop", spec("items", many, "maxItems", 200), new LinkedHashMap<>(), null, BODY, null);
        List<Integer> huge = java.util.stream.IntStream.range(0, 1001).boxed().toList();
        MagratheaLoop.Step capped = MagratheaLoop.step(
                "loop", spec("items", huge, "maxItems", 5000), new LinkedHashMap<>(), null, BODY, null);

        assertThat(defaulted.failure()).contains("101 items").contains("100");
        assertThat(defaulted.vars()).isEmpty();
        assertThat(raised.outcome()).isEqualTo("each");
        assertThat(capped.failure()).contains("1000");
    }

    @Test
    void an_inner_loop_gives_index_and_count_back_to_the_enclosing_loop_when_it_ends() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.putAll(MagratheaLoop.step("outer", spec("items", List.of("o1", "o2")), vars, null, Set.of("inner"), null)
                .vars());
        vars.putAll(MagratheaLoop.step(
                        "inner", spec("items", List.of("i1", "i2", "i3")), vars, "outer", Set.of("work"), "outer")
                .vars());
        assertThat(vars).containsEntry("index", 0).containsEntry("count", 3);

        MagratheaLoop.Step step = null;
        for (int i = 0; i < 3; i++) {
            step = MagratheaLoop.step(
                    "inner", spec("items", List.of("i1", "i2", "i3")), vars, "work", Set.of("work"), "outer");
            vars.putAll(step.vars());
        }

        assertThat(step.outcome()).isEqualTo("done");
        assertThat(vars).containsEntry("index", 0).containsEntry("count", 2);
    }

    @Test
    void an_empty_inner_loop_leaves_the_enclosing_loops_place_alone() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.putAll(MagratheaLoop.step("outer", spec("items", List.of("o1", "o2")), vars, null, Set.of("inner"), null)
                .vars());

        vars.putAll(MagratheaLoop.step("inner", spec("items", List.of()), vars, "outer", Set.of("work"), "outer")
                .vars());

        assertThat(vars).containsEntry("index", 0).containsEntry("count", 2);
    }

    @Test
    void a_collect_that_resolves_to_nothing_is_kept_as_a_null_result_per_element() {
        Map<String, Object> vars = new LinkedHashMap<>();
        Map<String, Object> spec = spec("items", List.of("a", "b"), "collect", null);

        run(vars, spec, "prepare");
        run(vars, spec, "check");
        MagratheaLoop.Step last = run(vars, spec, "check");

        assertThat(last.outcome()).isEqualTo("done");
        assertThat(last.output()).isEqualTo(java.util.Arrays.asList(null, null));
    }
}
