package de.mhus.vance.brain.documents.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Scheduler tick behaviour. The collaborators ({@link DocumentSummaryDriver},
 * {@link DocumentService}, {@link SettingService}, …) are fully mocked —
 * we only verify the orchestration: setting gate, claim call, per-doc
 * driver dispatch, and the failure-path release.
 */
class DocumentSummarySchedulerTest {

    private static final String NODE = "maya-prosser";

    private ProjectService projectService;
    private LocationService locationService;
    private ClusterService clusterService;
    private SettingService settingService;
    private DocumentService documentService;
    private DocumentSummaryDriver driver;
    private DocumentSummaryScheduler scheduler;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        locationService = mock(LocationService.class);
        clusterService = mock(ClusterService.class);
        settingService = mock(SettingService.class);
        documentService = mock(DocumentService.class);
        driver = mock(DocumentSummaryDriver.class);
        scheduler = new DocumentSummaryScheduler(
                projectService, locationService, clusterService, settingService, documentService, driver);
        ReflectionTestUtils.setField(scheduler, "batchSize", 10);
        ReflectionTestUtils.setField(scheduler, "claimTtlMinutes", 10);
        when(locationService.getPodAddress()).thenReturn("pod-a");
        when(clusterService.selfNodeName()).thenReturn(NODE);
    }

    @Test
    void tick_noProjects_doesNothing() {
        when(projectService.findRunningByHomeCluster(NODE)).thenReturn(List.of());

        scheduler.tick();

        verify(documentService, never()).claimForSummary(
                any(), any(), any(), anyInt(), any(Duration.class));
        verify(driver, never()).run(any(), any());
    }

    @Test
    void tick_settingDisabled_skipsProject() {
        ProjectDocument project = project("t1", "p1");
        when(projectService.findRunningByHomeCluster(NODE)).thenReturn(List.of(project));
        when(settingService.getBooleanValueCascade(
                eq("t1"), eq("p1"), eq(null), eq("autoSummary.enabled"), anyBoolean()))
                .thenReturn(false);

        scheduler.tick();

        verify(documentService, never()).claimForSummary(
                any(), any(), any(), anyInt(), any(Duration.class));
        verify(driver, never()).run(any(), any());
    }

    @Test
    void tick_settingEnabledNoDirtyDocs_noDriverCall() {
        ProjectDocument project = project("t1", "p1");
        when(projectService.findRunningByHomeCluster(NODE)).thenReturn(List.of(project));
        when(settingService.getBooleanValueCascade(
                eq("t1"), eq("p1"), eq(null), eq("autoSummary.enabled"), anyBoolean()))
                .thenReturn(true);
        when(documentService.claimForSummary(
                eq("t1"), eq("p1"), eq("pod-a"), eq(10), any(Duration.class)))
                .thenReturn(List.of());

        scheduler.tick();

        verify(driver, never()).run(any(), any());
    }

    @Test
    void tick_claimsDocs_dispatchesEachToDriver() {
        ProjectDocument project = project("t1", "p1");
        DocumentDocument d1 = doc("doc-1");
        DocumentDocument d2 = doc("doc-2");
        when(projectService.findRunningByHomeCluster(NODE)).thenReturn(List.of(project));
        when(settingService.getBooleanValueCascade(
                eq("t1"), eq("p1"), eq(null), eq("autoSummary.enabled"), anyBoolean()))
                .thenReturn(true);
        when(documentService.claimForSummary(
                eq("t1"), eq("p1"), eq("pod-a"), eq(10), any(Duration.class)))
                .thenReturn(List.of(d1, d2));

        scheduler.tick();

        verify(driver).run(project, d1);
        verify(driver).run(project, d2);
        verify(documentService, never()).releaseClaim(any());
    }

    @Test
    void tick_driverFails_releasesClaimAndContinues() {
        ProjectDocument project = project("t1", "p1");
        DocumentDocument d1 = doc("doc-1");
        DocumentDocument d2 = doc("doc-2");
        when(projectService.findRunningByHomeCluster(NODE)).thenReturn(List.of(project));
        when(settingService.getBooleanValueCascade(
                eq("t1"), eq("p1"), eq(null), eq("autoSummary.enabled"), anyBoolean()))
                .thenReturn(true);
        when(documentService.claimForSummary(
                eq("t1"), eq("p1"), eq("pod-a"), eq(10), any(Duration.class)))
                .thenReturn(List.of(d1, d2));
        doThrow(new RuntimeException("LLM blew up")).when(driver).run(project, d1);

        scheduler.tick();

        // Failed doc → claim released; survivor still runs.
        verify(documentService).releaseClaim("doc-1");
        verify(driver).run(project, d2);
    }

    @Test
    void tick_claimQueryFails_movesOnToNextProject() {
        ProjectDocument p1 = project("t1", "p1");
        ProjectDocument p2 = project("t1", "p2");
        when(projectService.findRunningByHomeCluster(NODE)).thenReturn(List.of(p1, p2));
        when(settingService.getBooleanValueCascade(
                any(), any(), eq(null), eq("autoSummary.enabled"), anyBoolean()))
                .thenReturn(true);
        when(documentService.claimForSummary(
                eq("t1"), eq("p1"), eq("pod-a"), eq(10), any(Duration.class)))
                .thenThrow(new RuntimeException("Mongo timeout"));
        when(documentService.claimForSummary(
                eq("t1"), eq("p2"), eq("pod-a"), eq(10), any(Duration.class)))
                .thenReturn(List.of(doc("doc-x")));

        scheduler.tick();

        verify(driver, times(1)).run(eq(p2), any());
    }

    @Test
    void tick_defaultBooleanIsTrue() {
        ProjectDocument project = project("t1", "p1");
        when(projectService.findRunningByHomeCluster(NODE)).thenReturn(List.of(project));
        // Settings cascade has nothing → cascade returns default. We
        // assert the scheduler passes `true` as the default, i.e.
        // feature opt-out semantics.
        when(settingService.getBooleanValueCascade(
                eq("t1"), eq("p1"), eq(null), eq("autoSummary.enabled"),
                /*defaultValue*/ eq(true)))
                .thenReturn(true);
        when(documentService.claimForSummary(
                any(), any(), any(), anyInt(), any(Duration.class)))
                .thenReturn(List.of());

        scheduler.tick();

        verify(settingService).getBooleanValueCascade(
                eq("t1"), eq("p1"), eq(null), eq("autoSummary.enabled"), eq(true));
    }

    // ── factory helpers ────────────────────────────────────────────────

    private static ProjectDocument project(String tenant, String name) {
        ProjectDocument p = new ProjectDocument();
        p.setTenantId(tenant);
        p.setName(name);
        return p;
    }

    private static DocumentDocument doc(String id) {
        return DocumentDocument.builder().id(id).path(id + ".md").build();
    }
}
