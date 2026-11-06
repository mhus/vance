package de.mhus.vance.shared.magrathea;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The body of a loop follows from the arrows: what {@code each} reaches without passing the loop itself. */
class MagratheaLoopBodyTest {

    private static Map<String, MagratheaStateSpec> states(String yaml) {
        return MagratheaWorkflowLoader.parseYaml("demo", yaml).states();
    }

    private static final String SIMPLE = """
            start: loop
            states:
              loop:
                type: for_each_task
                items: "${state.list}"
                on: { each: first, done: report }
              first:
                type: condition_task
                transitions:
                  - else: second
              second:
                type: condition_task
                transitions:
                  - else: loop
              report:
                type: terminal
                outcome: success
            """;

    @Test
    void the_body_is_what_each_reaches_before_the_arrow_back() {
        assertThat(MagratheaLoopBody.of(states(SIMPLE), "loop")).containsExactly("first", "second");
    }

    @Test
    void the_state_after_done_is_not_part_of_the_body() {
        assertThat(MagratheaLoopBody.of(states(SIMPLE), "loop")).doesNotContain("report", "loop");
    }

    @Test
    void a_branch_in_the_body_belongs_to_it_and_so_does_a_catch_target() {
        Map<String, MagratheaStateSpec> states = states("""
                start: loop
                states:
                  loop:
                    type: for_each_task
                    items: "${state.list}"
                    on: { each: work, done: end }
                  work:
                    type: shell_task
                    run: "true"
                    on: { success: loop }
                    catch: { technical_error: recover }
                  recover:
                    type: condition_task
                    transitions:
                      - else: loop
                  end:
                    type: terminal
                    outcome: success
                """);

        assertThat(MagratheaLoopBody.of(states, "loop")).containsExactlyInAnyOrder("work", "recover");
    }

    @Test
    void a_state_that_breaks_out_stays_in_the_body_but_what_it_leads_to_does_not() {
        Map<String, MagratheaStateSpec> states = states("""
                start: loop
                states:
                  loop:
                    type: for_each_task
                    items: "${state.list}"
                    on: { each: check, done: end }
                  check:
                    type: shell_task
                    run: "true"
                    on: { success: loop, stop: abort }
                  abort:
                    type: terminal
                    outcome: failure
                  end:
                    type: terminal
                    outcome: success
                """);

        // 'abort' is reached from the body: it is part of what one pass can touch. A terminal never leads
        // back, which is what makes it a break; for the engine that is enough — it is only asked whether the
        // state before the loop was in the body.
        assertThat(MagratheaLoopBody.of(states, "loop")).containsExactlyInAnyOrder("check", "abort");
    }

    @Test
    void nested_loops_each_have_their_own_body() {
        Map<String, MagratheaStateSpec> states = states("""
                start: outer
                states:
                  outer:
                    type: for_each_task
                    items: "${state.rows}"
                    on: { each: inner, done: end }
                  inner:
                    type: for_each_task
                    items: "${state.row}"
                    on: { each: cell, done: outer }
                  cell:
                    type: condition_task
                    transitions:
                      - else: inner
                  end:
                    type: terminal
                    outcome: success
                """);

        assertThat(MagratheaLoopBody.of(states, "inner")).containsExactly("cell");
        // The outer body reaches the inner loop, its cell and — through the inner loop's done — the outer loop
        // again, where it stops.
        assertThat(MagratheaLoopBody.of(states, "outer")).containsExactlyInAnyOrder("inner", "cell");
    }

    @Test
    void an_unknown_state_or_a_state_that_is_no_loop_has_an_empty_body() {
        assertThat(MagratheaLoopBody.of(states(SIMPLE), "nope")).isEmpty();
        assertThat(MagratheaLoopBody.of(states(SIMPLE), "first")).isEmpty();
    }
}
