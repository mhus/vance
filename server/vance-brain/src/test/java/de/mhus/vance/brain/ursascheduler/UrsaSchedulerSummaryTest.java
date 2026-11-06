package de.mhus.vance.brain.ursascheduler;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.ursascheduler.SchedulerSummary;
import de.mhus.vance.shared.ursascheduler.UrsaSchedulerLoader;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The list entry of a scheduler names the workflow it starts, so a workflow can find what starts it. */
class UrsaSchedulerSummaryTest {

    @Test
    void a_workflow_scheduler_names_its_workflow_and_no_recipe() {
        Instant next = Instant.parse("2026-11-06T06:00:00Z");
        SchedulerSummary s =
                UrsaSchedulerController.summaryOf(UrsaSchedulerLoader.parseValidated("audit", """
                        description: Daily audit
                        cron: "0 0 6 * * *"
                        workflow: daily-audit
                        """), null, next);

        assertThat(s.getWorkflow()).isEqualTo("daily-audit");
        assertThat(s.getRecipe()).isNull();
        assertThat(s.getCron()).isEqualTo("0 0 6 * * *");
        assertThat(s.getNextRunAt()).isEqualTo(next);
        assertThat(s.getTags()).isNull();
    }

    @Test
    void the_tags_of_a_scheduler_made_from_a_catalog_trigger_name_its_origin() {
        SchedulerSummary s =
                UrsaSchedulerController.summaryOf(UrsaSchedulerLoader.parseValidated("inbox", """
                        description: Polls slack/new-message
                        cron: "0 0/5 * * * *"
                        workflow: inbox
                        tags: [catalog, slack/new-message]
                        """), null, null);

        assertThat(s.getTags()).containsExactly("catalog", "slack/new-message");
    }

    @Test
    void a_recipe_scheduler_has_no_workflow() {
        SchedulerSummary s =
                UrsaSchedulerController.summaryOf(UrsaSchedulerLoader.parseValidated("chat", """
                        description: Chat
                        cron: "0 0 6 * * *"
                        recipe: ford
                        initialMessage: hi
                        """), null, null);

        assertThat(s.getWorkflow()).isNull();
        assertThat(s.getRecipe()).isEqualTo("ford");
    }
}
