package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.project.ProjectOwnerRequirementContributor.ProjectRef;
import de.mhus.vance.brain.project.ProjectOwnerRequirementService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The boot re-check that pins needs every hub with a live loop: a loop that
 * predates the contributor never asked for a recompute, so its hub would
 * stay unplaced and the heartbeat would never wake it.
 */
class TrillianOwnerRequirementTest {

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);

    @SuppressWarnings("unchecked")
    private final TrillianOwnerRequirement requirement = new TrillianOwnerRequirement(
            thinkProcessService, (ObjectProvider<ProjectOwnerRequirementService>) mock(ObjectProvider.class));

    @Test
    void projectsWithWork_lists_every_hub_with_a_live_loop() {
        when(thinkProcessService.findProjectsWithLiveEngine(TrillianUserEngine.NAME))
                .thenReturn(List.of(
                        Map.entry("acme", "_user__trillian-colin-1"), Map.entry("beta", "_user__trillian-void-2")));

        assertThat(requirement.projectsWithWork())
                .containsExactly(
                        new ProjectRef("acme", "_user__trillian-colin-1"),
                        new ProjectRef("beta", "_user__trillian-void-2"));
    }

    @Test
    void projectsWithWork_is_empty_without_loops() {
        when(thinkProcessService.findProjectsWithLiveEngine(TrillianUserEngine.NAME))
                .thenReturn(List.of());

        assertThat(requirement.projectsWithWork()).isEmpty();
    }
}
