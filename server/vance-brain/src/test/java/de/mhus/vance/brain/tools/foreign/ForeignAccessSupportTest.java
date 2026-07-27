package de.mhus.vance.brain.tools.foreign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage for the {@code foreign_*} access gate ({@link ForeignAccessSupport}).
 * The security guarantee is that cross-project resolution asks the pluggable
 * {@link PermissionService} the right question (the requested {@link Action} on
 * the target {@link Resource.Project}) and refuses SYSTEM projects / unknown
 * names <em>before</em> touching data — never a hard-coded role test.
 */
@ExtendWith(MockitoExtension.class)
class ForeignAccessSupportTest {

    private static final String TENANT = "acme";
    private static final String CURRENT = "research";
    private static final String USER = "alice";

    @Mock ProjectService projectService;
    @Mock PermissionService permissionService;
    @Mock SecurityContextFactory contextFactory;

    private ForeignAccessSupport support;
    private final SecurityContext subject = SecurityContext.user(USER, TENANT, List.of());

    @BeforeEach
    void setUp() {
        support = new ForeignAccessSupport(projectService, null, permissionService, contextFactory);
    }

    private ToolInvocationContext ctx() {
        return new ToolInvocationContext(TENANT, CURRENT, null, "proc-1", USER);
    }

    private ProjectDocument project(String name, ProjectKind kind) {
        return ProjectDocument.builder().name(name).kind(kind).build();
    }

    // ── resolveForeign ────────────────────────────────────────────

    @Test
    void resolveForeign_blankProjectId_throwsBeforeAnyLookup() {
        assertThatThrownBy(() -> support.resolveForeign("  ", ctx(), Action.READ))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("projectId");
        verify(projectService, never()).findByTenantAndName(eq(TENANT), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolveForeign_unknownProject_throws() {
        when(projectService.findByTenantAndName(TENANT, "ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> support.resolveForeign("ghost", ctx(), Action.READ))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not found");
        verify(permissionService, never()).enforce(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolveForeign_systemProject_refusedWithoutPermissionCheck() {
        when(projectService.findByTenantAndName(TENANT, "_vance"))
                .thenReturn(Optional.of(project("_vance", ProjectKind.SYSTEM)));

        assertThatThrownBy(() -> support.resolveForeign("_vance", ctx(), Action.READ))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("SYSTEM");
        verify(permissionService, never()).enforce(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolveForeign_regularProject_enforcesRequestedActionAndReturns() {
        ProjectDocument other = project("marketing", ProjectKind.NORMAL);
        when(projectService.findByTenantAndName(TENANT, "marketing")).thenReturn(Optional.of(other));
        when(contextFactory.forToolSubject(TENANT, USER)).thenReturn(subject);

        ProjectDocument resolved = support.resolveForeign("marketing", ctx(), Action.READ);

        assertThat(resolved).isSameAs(other);
        verify(permissionService).enforce(
                eq(subject), eq(new Resource.Project(TENANT, "marketing")), eq(Action.READ));
    }

    @Test
    void resolveForeign_forwardsTheGivenAction_notAlwaysRead() {
        when(projectService.findByTenantAndName(TENANT, "marketing"))
                .thenReturn(Optional.of(project("marketing", ProjectKind.NORMAL)));
        when(contextFactory.forToolSubject(TENANT, USER)).thenReturn(subject);

        support.resolveForeign("marketing", ctx(), Action.DELETE);

        verify(permissionService).enforce(
                eq(subject), eq(new Resource.Project(TENANT, "marketing")), eq(Action.DELETE));
    }

    @Test
    void resolveForeign_permissionDenied_propagates() {
        when(projectService.findByTenantAndName(TENANT, "marketing"))
                .thenReturn(Optional.of(project("marketing", ProjectKind.NORMAL)));
        when(contextFactory.forToolSubject(TENANT, USER)).thenReturn(subject);
        doThrow(new RuntimeException("denied")).when(permissionService).enforce(
                eq(subject), eq(new Resource.Project(TENANT, "marketing")), eq(Action.READ));

        assertThatThrownBy(() -> support.resolveForeign("marketing", ctx(), Action.READ))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("denied");
    }

    // ── resolveTarget ─────────────────────────────────────────────

    @Test
    void resolveTarget_explicitName_resolvesThatProject() {
        ProjectDocument other = project("marketing", ProjectKind.NORMAL);
        when(projectService.findByTenantAndName(TENANT, "marketing")).thenReturn(Optional.of(other));

        assertThat(support.resolveTarget("marketing", ctx())).isSameAs(other);
    }

    @Test
    void resolveTarget_nullName_defaultsToCurrentProject() {
        ProjectDocument current = project(CURRENT, ProjectKind.NORMAL);
        when(projectService.findByTenantAndName(TENANT, CURRENT)).thenReturn(Optional.of(current));

        assertThat(support.resolveTarget(null, ctx())).isSameAs(current);
    }

    @Test
    void resolveTarget_noExplicitNameAndNoProjectScope_throws() {
        ToolInvocationContext noScope = new ToolInvocationContext(TENANT, null, null, null, USER);

        assertThatThrownBy(() -> support.resolveTarget(null, noScope))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("No target project");
    }

    @Test
    void resolveTarget_systemProject_rejected() {
        when(projectService.findByTenantAndName(TENANT, "_user_alice"))
                .thenReturn(Optional.of(project("_user_alice", ProjectKind.SYSTEM)));

        assertThatThrownBy(() -> support.resolveTarget("_user_alice", ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("SYSTEM");
    }

    @Test
    void resolveTarget_vanceStagingArea_allowed() {
        ProjectDocument vance = project("_vance", ProjectKind.SYSTEM);
        when(projectService.findByTenantAndName(TENANT, "_vance")).thenReturn(Optional.of(vance));

        assertThat(support.resolveTarget("_vance", ctx())).isSameAs(vance);
    }

    // ── reserved ──────────────────────────────────────────────────

    @Test
    void reserved_flagsUnderscoreNamespacesOnly() {
        assertThat(ForeignAccessSupport.reserved("_vance/recipes/x.yaml")).isTrue();
        assertThat(ForeignAccessSupport.reserved("_bin/1.md")).isTrue();
        assertThat(ForeignAccessSupport.reserved("notes/plan.md")).isFalse();
        assertThat(ForeignAccessSupport.reserved("documents/a/b.md")).isFalse();
        assertThat(ForeignAccessSupport.reserved(null)).isFalse();
    }
}
