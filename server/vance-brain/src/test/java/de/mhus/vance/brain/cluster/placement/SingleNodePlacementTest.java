package de.mhus.vance.brain.cluster.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SingleNodePlacementTest {

    private ProjectService projectService;
    private ProjectLifecycleService lifecycleService;
    private SingleNodePlacement placement;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        projectService = mock(ProjectService.class);
        lifecycleService = mock(ProjectLifecycleService.class);
        ObjectProvider<ProjectLifecycleService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(lifecycleService);
        placement = new SingleNodePlacement(projectService, provider);
    }

    private static ProjectDocument project(String name) {
        return ProjectDocument.builder().tenantId("acme").name(name).build();
    }

    @Test
    void place_bringsTheProjectOnThisPod() {
        PlacementDecision decision = placement.place(project("alpha"), PlacementTrigger.CREATE);

        assertThat(decision).isInstanceOf(PlacementDecision.Here.class);
        verify(lifecycleService).bring("acme", "alpha");
    }

    @Test
    void place_neverAsksForCapacity_soNothingIsEverFull() {
        // Every trigger lands here, including the ones that would otherwise be
        // spread across pods.
        for (PlacementTrigger trigger : PlacementTrigger.values()) {
            assertThat(placement.place(project("alpha"), trigger)).isInstanceOf(PlacementDecision.Here.class);
        }
    }

    @Test
    void place_refusesTheHomelessTenantProject() {
        ProjectDocument homeless = project("_tenant");

        assertThatThrownBy(() -> placement.place(homeless, PlacementTrigger.CREATE))
                .isInstanceOf(IllegalArgumentException.class);
        verify(lifecycleService, never()).bring(any(), any());
    }

    @Test
    void everyProjectButTheHomelessOneIsEligible() {
        assertThat(placement.isEligibleHere(project("alpha"))).isTrue();
        assertThat(placement.isEligibleHere(project("_tenant"))).isFalse();
    }

    @Test
    void eligibilityByName_unknownProjectIsLeftToTheCallersNotFoundPath() {
        when(projectService.findByTenantAndName("acme", "ghost")).thenReturn(Optional.empty());

        assertThat(placement.isEligibleHere("acme", "ghost")).isTrue();
    }

    @Test
    void thereIsNoCap() {
        assertThat(placement.localHeadroom()).isEqualTo(Integer.MAX_VALUE);
    }
}
