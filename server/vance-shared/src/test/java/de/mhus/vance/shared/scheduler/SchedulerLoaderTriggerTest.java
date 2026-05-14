package de.mhus.vance.shared.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Focused tests for the scheduler's {@code recipe:} vs {@code workflow:}
 * exclusivity — the trigger-target discriminator added when Hactar
 * workflows became a scheduler-spawnable target alongside recipes.
 */
class SchedulerLoaderTriggerTest {

    private final SchedulerLoader loader = new SchedulerLoader(null);

    @Test
    void recipe_trigger_parses_and_isWorkflowTrigger_is_false() {
        String yaml = """
                description: "Recipe scheduler"
                cron: "0 0 8 * * *"
                recipe: "analyze"
                """;
        ResolvedScheduler r = loader.validateYaml("daily", yaml);

        assertThat(r.recipe()).isEqualTo("analyze");
        assertThat(r.workflow()).isNull();
        assertThat(r.isWorkflowTrigger()).isFalse();
    }

    @Test
    void workflow_trigger_parses_and_isWorkflowTrigger_is_true() {
        String yaml = """
                description: "Workflow scheduler"
                cron: "0 0 6 * * *"
                workflow: "daily-audit"
                params:
                  project: "main"
                """;
        ResolvedScheduler r = loader.validateYaml("audit", yaml);

        assertThat(r.workflow()).isEqualTo("daily-audit");
        assertThat(r.recipe()).isNull();
        assertThat(r.isWorkflowTrigger()).isTrue();
        assertThat(r.params()).containsEntry("project", "main");
    }

    @Test
    void both_recipe_and_workflow_set_rejected() {
        String yaml = """
                description: "Ambiguous trigger"
                cron: "0 0 8 * * *"
                recipe: "analyze"
                workflow: "daily-audit"
                """;
        assertThatThrownBy(() -> loader.validateYaml("amb", yaml))
                .isInstanceOf(SchedulerLoader.SchedulerParseException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void neither_recipe_nor_workflow_set_rejected() {
        String yaml = """
                description: "No trigger"
                cron: "0 0 8 * * *"
                """;
        assertThatThrownBy(() -> loader.validateYaml("none", yaml))
                .isInstanceOf(SchedulerLoader.SchedulerParseException.class)
                .hasMessageContaining("missing trigger target");
    }
}
