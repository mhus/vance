package de.mhus.vance.brain.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.insights.LlmBudgetStatusDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.llmusage.BudgetScope;
import de.mhus.vance.shared.llmusage.LlmBudget;
import de.mhus.vance.shared.llmusage.LlmBudgetService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /usage/budget}: who sees which level. The tenant level carries the
 * whole tenant's spend, so it is for tenant admins only — a project admin sees
 * the project level and nothing above it.
 */
class LlmUsageReportControllerBudgetTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";

    private final LlmBudgetService budgetService = mock(LlmBudgetService.class);
    private final RequestAuthority authority = mock(RequestAuthority.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final LlmUsageReportController controller =
            new LlmUsageReportController(mock(LlmUsageReportService.class), budgetService, authority);

    @BeforeEach
    void bothLevelsExist() {
        when(budgetService.status(eq(TENANT), any()))
                .thenReturn(List.of(
                        new LlmBudgetService.ScopeStatus(BudgetScope.PROJECT, PROJECT, LlmBudget.NONE, List.of()),
                        new LlmBudgetService.ScopeStatus(BudgetScope.TENANT, "_tenant", LlmBudget.NONE, List.of())));
    }

    @Test
    void projectAdmin_withoutTenantAdmin_seesOnlyTheProjectLevel() {
        when(authority.check(request, new Resource.Tenant(TENANT), Action.ADMIN))
                .thenReturn(false);

        List<LlmBudgetStatusDto> levels = controller.budget(TENANT, PROJECT, request);

        assertThat(levels).extracting(LlmBudgetStatusDto::getScope).containsExactly("project");
        verify(authority).enforce(request, new Resource.Project(TENANT, PROJECT), Action.ADMIN);
        verify(authority, never()).enforce(request, new Resource.Tenant(TENANT), Action.ADMIN);
    }

    @Test
    void projectAdmin_whoIsAlsoTenantAdmin_seesBothLevels() {
        when(authority.check(request, new Resource.Tenant(TENANT), Action.ADMIN))
                .thenReturn(true);

        List<LlmBudgetStatusDto> levels = controller.budget(TENANT, PROJECT, request);

        assertThat(levels).extracting(LlmBudgetStatusDto::getScope).containsExactly("project", "tenant");
    }

    @Test
    void withoutProject_tenantAdminIsRequired() {
        Resource tenant = new Resource.Tenant(TENANT);
        doThrow(new PermissionDeniedException(SecurityContext.user("bob", TENANT, List.of()), tenant, Action.ADMIN))
                .when(authority)
                .enforce(request, tenant, Action.ADMIN);

        assertThatThrownBy(() -> controller.budget(TENANT, null, request))
                .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> controller.budget(TENANT, "  ", request))
                .isInstanceOf(PermissionDeniedException.class);
        verify(budgetService, never()).status(any(), any());
    }

    @Test
    void nonProjectAdmin_isRefused_beforeAnythingIsRead() {
        Resource project = new Resource.Project(TENANT, PROJECT);
        doThrow(new PermissionDeniedException(SecurityContext.user("bob", TENANT, List.of()), project, Action.ADMIN))
                .when(authority)
                .enforce(request, project, Action.ADMIN);

        assertThatThrownBy(() -> controller.budget(TENANT, PROJECT, request))
                .isInstanceOf(PermissionDeniedException.class);
        verify(budgetService, never()).status(any(), any());
    }
}
