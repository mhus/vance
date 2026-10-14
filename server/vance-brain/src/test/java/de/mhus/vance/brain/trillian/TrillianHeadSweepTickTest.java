package de.mhus.vance.brain.trillian;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The head sweep round: only the hubs this pod holds, only multi-headed
 * Trillians, and one Trillian that fails does not stop the others.
 */
class TrillianHeadSweepTickTest {

    private final ClusterService clusterService = mock(ClusterService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final TrillianWakeupService wakeupService = mock(TrillianWakeupService.class);
    private final TrillianNatureRegistry natureRegistry = mock(TrillianNatureRegistry.class);
    private final TrillianHeadService headService = mock(TrillianHeadService.class);
    private final TrillianNature multiHead = mock(TrillianNature.class);
    private final TrillianNature singleHead = mock(TrillianNature.class);

    private final TrillianHeadSweepTick tick =
            new TrillianHeadSweepTick(clusterService, projectService, wakeupService, natureRegistry, headService);

    private final ProjectDocument hub = hub("_user__trillian-colin-1");

    @BeforeEach
    void setUp() {
        when(clusterService.selfPodId()).thenReturn("pod-self");
        when(projectService.findRunningByHomePodId("pod-self")).thenReturn(List.of(hub));
        when(multiHead.multiHead()).thenReturn(true);
        when(singleHead.multiHead()).thenReturn(false);
        when(natureRegistry.resolve("colin")).thenReturn(multiHead);
        when(natureRegistry.resolve("adam")).thenReturn(singleHead);
    }

    @Test
    void theLoopsOfTheHeldHubs_areSwept() {
        ThinkProcessDocument loop = loop("loop-1", "colin");
        when(wakeupService.liveLoopsIn(List.of(hub))).thenReturn(List.of(loop));

        tick.tick();

        verify(headService).sweepLoop(eq(loop), any());
    }

    @Test
    void aPodHoldingNoHub_listsNothing() {
        when(projectService.findRunningByHomePodId("pod-self")).thenReturn(List.of());

        tick.tick();

        verify(wakeupService, never()).liveLoopsIn(any());
        verify(headService, never()).sweepLoop(any(), any());
    }

    @Test
    void aSingleHeadedTrillian_hasNoHeadsToSweep() {
        when(wakeupService.liveLoopsIn(List.of(hub))).thenReturn(List.of(loop("loop-1", "adam")));

        tick.tick();

        verify(headService, never()).sweepLoop(any(), any());
    }

    @Test
    void oneFailingTrillian_doesNotStopTheOthers() {
        ThinkProcessDocument broken = loop("loop-1", "colin");
        ThinkProcessDocument fine = loop("loop-2", "colin");
        when(wakeupService.liveLoopsIn(List.of(hub))).thenReturn(List.of(broken, fine));
        doThrow(new IllegalStateException("boom")).when(headService).sweepLoop(eq(broken), any());

        tick.tick();

        verify(headService).sweepLoop(eq(fine), any());
    }

    @Test
    void aNatureThatCannotBeResolved_doesNotStopTheOthers() {
        ThinkProcessDocument broken = loop("loop-1", "gone");
        ThinkProcessDocument fine = loop("loop-2", "colin");
        when(natureRegistry.resolve("gone")).thenThrow(new IllegalStateException("unknown nature"));
        when(wakeupService.liveLoopsIn(List.of(hub))).thenReturn(List.of(broken, fine));

        tick.tick();

        verify(headService).sweepLoop(eq(fine), any());
    }

    private static ProjectDocument hub(String name) {
        ProjectDocument doc = new ProjectDocument();
        doc.setTenantId("acme");
        doc.setName(name);
        return doc;
    }

    private static ThinkProcessDocument loop(String id, String nature) {
        ThinkProcessDocument loop = new ThinkProcessDocument();
        loop.setId(id);
        loop.setTenantId("acme");
        loop.setProjectId("_user__trillian-colin-1");
        loop.getEngineParams().put(TrillianSessionBootstrapper.PARAM_NATURE, nature);
        return loop;
    }
}
