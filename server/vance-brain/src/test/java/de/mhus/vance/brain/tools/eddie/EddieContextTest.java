package de.mhus.vance.brain.tools.eddie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.ScratchpadService;
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
 * LLMs from drifting to a hallucinated projectId.
 */
class EddieContextTest {

    private static final String TENANT = "acme";
    private static final String SESSION = "sess1";
    private static final String PROCESS_ID = "proc-1";
    private static final String INHERITED = "inherited-project";
    private static final String HALLUCINATED = "instant-hole";

    private ScratchpadService scratchpad;
    private ProjectService projectService;
    private ThinkProcessService thinkProcessService;
    private EddieContext eddieContext;

    @BeforeEach
    void setUp() {
        scratchpad = mock(ScratchpadService.class);
        projectService = mock(ProjectService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        eddieContext = new EddieContext(scratchpad, projectService, thinkProcessService);
        when(scratchpad.get(TENANT, PROCESS_ID, EddieContext.ACTIVE_PROJECT_SLOT))
                .thenReturn(Optional.empty());
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
        void staleActiveSlotIsIgnored_inheritedUsedInstead() {
            // Sub-process worker called project_switch and polluted
            // its active-slot with a hallucinated project name. Now
            // a follow-up tool call without explicit projectId must
            // not silently use that slot.
            arrangeProcess(PROCESS_ID, "parent-x");
            arrangeProject(INHERITED, ProjectKind.NORMAL);
            MemoryDocument slot = mock(MemoryDocument.class);
            when(slot.getContent()).thenReturn(HALLUCINATED);
            when(scratchpad.get(TENANT, PROCESS_ID, EddieContext.ACTIVE_PROJECT_SLOT))
                    .thenReturn(Optional.of(slot));

            ProjectDocument resolved = eddieContext.resolveProject(
                    Map.of(),
                    ctx(INHERITED, PROCESS_ID),
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
