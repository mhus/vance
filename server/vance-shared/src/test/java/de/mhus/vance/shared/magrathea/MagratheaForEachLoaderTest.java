package de.mhus.vance.shared.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import org.junit.jupiter.api.Test;

/** What the loader says about a {@code for_each_task}. */
class MagratheaForEachLoaderTest {

    private static String workflow(String loopFields) {
        return """
                start: loop
                states:
                  loop:
                    type: for_each_task
                %s
                  body:
                    type: condition_task
                    transitions:
                      - else: loop
                  end:
                    type: terminal
                    outcome: success
                """.formatted(loopFields.indent(4).stripTrailing());
    }

    private static final String GOOD = """
            items: "${state.messages}"
            as: message
            maxItems: 50
            collect: "${state.reply}"
            storeAs: handled
            on: { each: body, done: end }
            """;

    @Test
    void reads_a_loop_with_its_fields_in_the_spec_and_the_two_outcomes_in_on() {
        MagratheaStateSpec loop = MagratheaWorkflowLoader.parseYaml("demo", workflow(GOOD))
                .states()
                .get("loop");

        assertThat(loop.type()).isEqualTo(MagratheaTaskType.FOR_EACH_TASK);
        assertThat(loop.onOutcomes()).containsEntry("each", "body").containsEntry("done", "end");
        assertThat(loop.spec()).containsEntry("items", "${state.messages}").containsEntry("as", "message");
        assertThat(loop.storeAs()).isEqualTo("handled");
    }

    @Test
    void items_each_and_done_are_required() {
        assertThatThrownBy(() -> MagratheaWorkflowLoader.parseYaml("demo", workflow("on: { each: body, done: end }")))
                .hasMessageContaining("needs 'items'");
        assertThatThrownBy(() ->
                        MagratheaWorkflowLoader.parseYaml("demo", workflow("items: \"${state.x}\"\non: { done: end }")))
                .hasMessageContaining("'on: each");
        assertThatThrownBy(() -> MagratheaWorkflowLoader.parseYaml(
                        "demo", workflow("items: \"${state.x}\"\non: { each: body }")))
                .hasMessageContaining("'on: done");
    }

    @Test
    void the_element_name_must_be_usable_and_not_one_the_engine_sets() {
        for (String bad : new String[] {"index", "count", "two words", "1st"}) {
            assertThatThrownBy(() -> MagratheaWorkflowLoader.parseYaml(
                            "demo",
                            workflow("items: \"${state.x}\"\nas: \"" + bad + "\"\non: { each: body, done: end }")))
                    .hasMessageContaining("not a usable element name");
        }
    }

    @Test
    void max_items_is_between_one_and_a_thousand() {
        for (String bad : new String[] {"0", "1001", "-5"}) {
            assertThatThrownBy(() -> MagratheaWorkflowLoader.parseYaml(
                            "demo",
                            workflow("items: \"${state.x}\"\nmaxItems: " + bad + "\non: { each: body, done: end }")))
                    .hasMessageContaining("maxItems must be between 1 and 1000");
        }
        assertThat(MagratheaWorkflowLoader.parseYaml(
                                "demo",
                                workflow("items: \"${state.x}\"\nmaxItems: 1000\non: { each: body, done: end }"))
                        .states())
                .containsKey("loop");
    }

    @Test
    void a_loop_whose_each_leads_back_to_itself_has_no_body() {
        assertThatThrownBy(() -> MagratheaWorkflowLoader.parseYaml(
                        "demo", workflow("items: \"${state.x}\"\non: { each: loop, done: end }")))
                .hasMessageContaining("no body");
    }

    @Test
    void an_arrow_to_a_state_that_does_not_exist_is_still_refused() {
        assertThatThrownBy(() -> MagratheaWorkflowLoader.parseYaml(
                        "demo", workflow("items: \"${state.x}\"\non: { each: nowhere, done: end }")))
                .hasMessageContaining("unknown state 'nowhere'");
    }
}
