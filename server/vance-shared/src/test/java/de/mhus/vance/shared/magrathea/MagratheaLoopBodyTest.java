package de.mhus.vance.shared.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ── the loop an end state ends ──────────────────────────────────

    private static final String WITH_END = """
            start: loop
            states:
              loop:
                type: for_each_task
                items: "${state.list}"
                on: { each: work, done: report }
              work:
                type: condition_task
                transitions:
                  - else: next
              next:
                type: end
              report:
                type: terminal
                outcome: success
            """;

    @Test
    void an_end_state_belongs_to_the_body_it_closes_and_ends_that_loop() {
        Map<String, MagratheaStateSpec> states = states(WITH_END);

        assertThat(MagratheaLoopBody.of(states, "loop")).containsExactly("work", "next");
        assertThat(MagratheaLoopBody.enclosingLoop(states, "next")).contains("loop");
        assertThat(MagratheaLoopBody.enclosingLoop(states, "work")).contains("loop");
    }

    @Test
    void a_state_outside_every_loop_body_has_no_enclosing_loop() {
        Map<String, MagratheaStateSpec> states = states(WITH_END);

        assertThat(MagratheaLoopBody.enclosingLoop(states, "report")).isEmpty();
        assertThat(MagratheaLoopBody.enclosingLoop(states, "loop")).isEmpty();
    }

    private static final String NESTED_ENDS = """
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

    @Test
    void in_nested_loops_an_end_ends_the_innermost_loop_whose_body_it_is_in() {
        Map<String, MagratheaStateSpec> states = states(NESTED_ENDS);

        // cell_end lies in the inner body, which is part of the outer one: the inner loop is meant.
        assertThat(MagratheaLoopBody.enclosingLoop(states, "cell_end")).contains("inner");
        // row_end is reached through the inner loop's done, so it is in the outer body only.
        assertThat(MagratheaLoopBody.enclosingLoop(states, "row_end")).contains("outer");
    }

    @Test
    void an_end_in_the_bodies_of_two_loops_that_do_not_lie_one_inside_the_other_is_refused_by_the_loader() {
        assertThatThrownBy(() -> states("""
                        start: a
                        states:
                          a:
                            type: for_each_task
                            items: "${state.x}"
                            on: { each: a1, done: b }
                          a1:
                            type: condition_task
                            transitions:
                              - else: shared
                          b:
                            type: for_each_task
                            items: "${state.y}"
                            on: { each: b1, done: finish }
                          b1:
                            type: condition_task
                            transitions:
                              - else: shared
                          shared:
                            type: end
                          finish:
                            type: terminal
                            outcome: success
                        """))
                .hasMessageContaining("state 'shared' is in the bodies of the loops")
                .hasMessageContaining("do not lie one inside the other");
    }
}
