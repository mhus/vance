package de.mhus.vance.brain.ursaeventtrigger;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.ursaevents.EventSummary;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
import org.junit.jupiter.api.Test;

/** The list entry of an event carries the workflow it starts and, when there is one, its catalog origin. */
class UrsaEventAdminSummaryTest {

    private final UrsaEventLoader loader = new UrsaEventLoader(null);

    @Test
    void a_catalog_event_names_its_origin_and_its_workflow() {
        EventSummary s = UrsaEventAdminController.toSummary(loader.validateYaml("gh", """
                workflow: on-push
                from: github/push
                auth:
                  public: true
                """));

        assertThat(s.getWorkflow()).isEqualTo("on-push");
        assertThat(s.getFrom()).isEqualTo("github/push");
    }

    @Test
    void an_event_written_by_hand_has_no_origin() {
        EventSummary s = UrsaEventAdminController.toSummary(loader.validateYaml("ping", """
                workflow: ping
                auth:
                  public: true
                """));

        assertThat(s.getFrom()).isNull();
    }
}
