package de.mhus.vance.brain.tools.eddie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Focuses on {@link EddieContext#resolveProject} — in particular
 * the sub-process clamp that prevents Marvin/Vogon-spawned worker
 * LLMs from drifting to a hallucinated projectId — and the
 * working-project (spot) read/write that now sits directly on
 * {@code ThinkProcessDocument.workingProjectId} instead of the
 * legacy scratchpad slot.
 */
class EddieContextTest {

    private static final String TENANT = "acme";
    private static final String SESSION = "sess1";
    private static final String PROCESS_ID = "proc-1";
    private static final String INHERITED = "inherited-project";
    private static final String HALLUCINATED = "instant-hole";

    private ProjectService projectService;
    private ThinkProcessService thinkProcessService;
    private de.mhus.vance.shared.permission.PermissionService permissionService;
    private de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;
    private EddieContext eddieContext;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        permissionService = mock(de.mhus.vance.shared.permission.PermissionService.class);
        contextFactory = mock(de.mhus.vance.brain.permission.SecurityContextFactory.class);
        // These tests cover project-resolution semantics, not authz — allow all.
        eddieContext = new EddieContext(
                projectService, thinkProcessService, permissionService, contextFactory);
    }

    @Nested
    class SubProcessClamp {

        @Test
        void hallucinatedProjectIdIsIgnored_inheritedUsedInstead() {
            arrangeProcess(PROCESS_ID, /*parent*/ "parent-x");
            arrangeProject(INHERITED, ProjectKind.NORMAL);

            ProjectDocument resolved = eddieContext.resolveProject(
                    Map.of("projectId", HALLUCINATED),
                    ctx(INHERITED, PROCESS_ID),
                    /*allowSystem*/ false);

            assertThat(resolved.getName()).isEqualTo(INHERITED);
        }

        @Test
        void matchingProjectIdIsAccepted() {
            arrangeProcess(PROCESS_ID, "parent-x");
            arrangeProject(INHERITED, ProjectKind.NORMAL);

            ProjectDocument resolved = eddieContext.resolveProject(
                    Map.of("projectId", INHERITED),
                    ctx(INHERITED, PROCESS_ID),
                    false);

            assertThat(resolved.getName()).isEqualTo(INHERITED);
        }

        @Test
        void noExplicitParam_inheritedUsed() {
            arrangeProcess(PROCESS_ID, "parent-x");
            arrangeProject(INHERITED, ProjectKind.NORMAL);

            ProjectDocument resolved = eddieContext.resolveProject(
                    Map.of(),
                    ctx(INHERITED, PROCESS_ID),
                    false);

            assertThat(resolved.getName()).isEqualTo(INHERITED);
        }

        @Test
        void subProcessWithoutInheritedProject_throws() {
            arrangeProcess(PROCESS_ID, "parent-x");

            assertThatThrownBy(() -> eddieContext.resolveProject(
                            Map.of("projectId", HALLUCINATED),
                            ctx(/*projectId*/ null, PROCESS_ID),
                            false))
                    .isInstanceOf(ToolException.class)
                    .hasMessageContaining("Sub-process invoked without an inherited projectId");
        }

        @Test
        void staleWorkingProjectIsIgnored_inheritedUsedInstead() {
            // Sub-process worker has a leaked workingProjectId on its
            // process record (impossible in normal operation — workers
            // never write that field — but the sub-process clamp must
            // still ignore it so a corrupted record cannot escape the
            // inherited project scope).
            arrangeProcess(PROCESS_ID, "parent-x");
            arrangeProject(INHERITED, ProjectKind.NORMAL);

            ToolInvocationContext withStaleSpot = new ToolInvocationContext(
                    TENANT, INHERITED, SESSION, PROCESS_ID, null, HALLUCINATED);

            ProjectDocument resolved = eddieContext.resolveProject(
                    Map.of(),
                    withStaleSpot,
                    false);

            assertThat(resolved.getName()).isEqualTo(INHERITED);
        }
    }

    @Nested
    class TopLevelPassThrough {

        @Test
        void explicitProjectIdHonored_whenNoParent() {
            arrangeProcess(PROCESS_ID, /*parent*/ null);
            arrangeProject("other-project", ProjectKind.NORMAL);

            ProjectDocument resolved = eddieContext.resolveProject(
                    Map.of("projectId", "other-project"),
                    ctx(INHERITED, PROCESS_ID),
                    false);

            assertThat(resolved.getName()).isEqualTo("other-project");
        }

        @Test
        void noProcessIdAtAll_treatedAsTopLevel() {
            // Admin/CLI flows can invoke tools without a process. They
            // must still be able to resolve a project via the explicit
            // param.
            arrangeProject("admin-pick", ProjectKind.NORMAL);

            ProjectDocument resolved = eddieContext.resolveProject(
                    Map.of("projectId", "admin-pick"),
                    new ToolInvocationContext(TENANT, null, null, null, null),
                    false);

            assertThat(resolved.getName()).isEqualTo("admin-pick");
        }
    }

    @Nested
    class WorkingProjectSpot {

        @Test
        void readActiveProject_prefersCtx_overMongoLookup() {
            // Live ctx-carried spot is the fast path — no Mongo round-trip.
            ToolInvocationContext withSpot = new ToolInvocationContext(
                    TENANT, INHERITED, SESSION, PROCESS_ID, null, "projA");

            Optional<String> spot = eddieContext.readActiveProject(withSpot);

            assertThat(spot).contains("projA");
        }

        @Test
        void readActiveProject_fallsBackToMongo_whenCtxLacksSpot() {
            // Legacy ToolInvocationContext built via the 5-arg constructor
            // carries null workingProjectId; EddieContext recovers the
            // value from the process record so existing call-sites keep
            // working through the migration.
            ThinkProcessDocument doc = new ThinkProcessDocument();
            doc.setId(PROCESS_ID);
            doc.setWorkingProjectId("projFromDb");
            when(thinkProcessService.findById(PROCESS_ID)).thenReturn(Optional.of(doc));

            Optional<String> spot = eddieContext.readActiveProject(ctx(INHERITED, PROCESS_ID));

            assertThat(spot).contains("projFromDb");
        }

        @Test
        void readActiveProject_returnsEmpty_whenNoSpotAnywhere() {
            ThinkProcessDocument doc = new ThinkProcessDocument();
            doc.setId(PROCESS_ID);
            when(thinkProcessService.findById(PROCESS_ID)).thenReturn(Optional.of(doc));

            assertThat(eddieContext.readActiveProject(ctx(INHERITED, PROCESS_ID))).isEmpty();
        }

        @Test
        void readActiveProject_returnsEmpty_whenNoProcessIdAtAll() {
            // Admin / CLI flows without a think-process scope.
            ToolInvocationContext bare = new ToolInvocationContext(
                    TENANT, null, null, null, null);

            assertThat(eddieContext.readActiveProject(bare)).isEmpty();
        }

        @Test
        void writeActiveProject_delegatesToServiceWithProcessId() {
            eddieContext.writeActiveProject(ctx(INHERITED, PROCESS_ID), "projTarget");

            verify(thinkProcessService).setWorkingProjectId(PROCESS_ID, "projTarget");
        }

        @Test
        void writeActiveProject_throws_whenNoProcessScope() {
            ToolInvocationContext bare = new ToolInvocationContext(
                    TENANT, null, null, null, null);

            assertThatThrownBy(() -> eddieContext.writeActiveProject(bare, "projTarget"))
                    .isInstanceOf(ToolException.class)
                    .hasMessageContaining("think-process scope");
        }
    }

    @Nested
    class ReadAuthorization {

        @Test
        void resolveProject_enforces_project_read_on_the_target() {
            arrangeProject("proj-x", ProjectKind.NORMAL);
            var subject = de.mhus.vance.shared.permission.SecurityContext.user(
                    "alice", TENANT, java.util.List.of());
            when(contextFactory.forToolSubject(TENANT, "alice")).thenReturn(subject);
            ToolInvocationContext c = new ToolInvocationContext(
                    TENANT, "proj-x", SESSION, "p2", "alice");

            eddieContext.resolveProject(Map.of("projectId", "proj-x"), c, false);

            verify(permissionService).enforce(
                    subject,
                    new de.mhus.vance.shared.permission.Resource.Project(TENANT, "proj-x"),
                    de.mhus.vance.shared.permission.Action.READ);
        }

        @Test
        void resolveProject_propagates_read_denial() {
            arrangeProject("secret", ProjectKind.NORMAL);
            ToolInvocationContext c = new ToolInvocationContext(
                    TENANT, "secret", SESSION, "p3", "bob");
            org.mockito.Mockito.doThrow(
                    new de.mhus.vance.shared.permission.PermissionDeniedException(
                            de.mhus.vance.shared.permission.SecurityContext.user("bob", TENANT, java.util.List.of()),
                            new de.mhus.vance.shared.permission.Resource.Project(TENANT, "secret"),
                            de.mhus.vance.shared.permission.Action.READ))
                    .when(permissionService).enforce(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any());

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    eddieContext.resolveProject(Map.of("projectId", "secret"), c, false))
                    .isInstanceOf(
                            de.mhus.vance.shared.permission.PermissionDeniedException.class);
        }
    }

    // ──────────────────── helpers ────────────────────

    private void arrangeProcess(String processId, String parentProcessId) {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId(processId);
        doc.setTenantId(TENANT);
        doc.setProjectId(INHERITED);
        doc.setSessionId(SESSION);
        doc.setParentProcessId(parentProcessId);
        when(thinkProcessService.findById(processId)).thenReturn(Optional.of(doc));
    }

    private void arrangeProject(String name, ProjectKind kind) {
        ProjectDocument p = new ProjectDocument();
        p.setName(name);
        p.setTenantId(TENANT);
        p.setKind(kind);
        when(projectService.findByTenantAndName(TENANT, name)).thenReturn(Optional.of(p));
    }

    private static ToolInvocationContext ctx(String projectId, String processId) {
        return new ToolInvocationContext(TENANT, projectId, SESSION, processId, null);
    }
}
