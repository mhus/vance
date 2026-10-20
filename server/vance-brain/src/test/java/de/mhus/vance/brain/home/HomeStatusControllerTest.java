package de.mhus.vance.brain.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.home.HomeStatusDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.llmusage.BudgetScope;
import de.mhus.vance.shared.llmusage.BudgetWindow;
import de.mhus.vance.shared.llmusage.LlmBudget;
import de.mhus.vance.shared.llmusage.LlmBudgetService;
import de.mhus.vance.shared.llmusage.LlmPowerService;
import de.mhus.vance.shared.llmusage.LlmPowerState;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.storage.StorageQuotaService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HomeStatusController}: each field is independent and nullable, the
 * controller must not fail the whole call when one component is unreachable.
 * That mirrors the fail-open philosophy of {@link LlmBudgetService} check()
 * and {@link StorageQuotaService} check() (R1/R5 in
 * {@code planning/storage-quota.md}).
 */
class HomeStatusControllerTest {

    private static final String TENANT = "acme";

    private LlmPowerService llmPowerService;
    private LlmBudgetService llmBudgetService;
    private StorageQuotaService storageQuotaService;
    private RequestAuthority authority;
    private HttpServletRequest request;
    private HomeStatusController controller;

    @BeforeEach
    void setUp() {
        llmPowerService = mock(LlmPowerService.class);
        llmBudgetService = mock(LlmBudgetService.class);
        storageQuotaService = mock(StorageQuotaService.class);
        authority = mock(RequestAuthority.class);
        request = mock(HttpServletRequest.class);
        when(authority.check(eq(request), any(Resource.class), eq(Action.READ))).thenReturn(true);
        controller = new HomeStatusController(llmPowerService, llmBudgetService, storageQuotaService, authority);
    }

    @Test
    void allThreeAvailable_returnsAllFields() {
        when(llmPowerService.load(TENANT)).thenReturn(LlmPowerState.ALL_ON);
        when(llmBudgetService.status(eq(TENANT), eq(null))).thenReturn(List.of(tenantScope(73.0)));
        when(storageQuotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 800_000L, 1_000_000L, 80, false, true));

        HomeStatusDto s = controller.status(request, TENANT, null);

        assertThat(s.getLlmPowerOn()).isTrue();
        assertThat(s.getLlmBudgetPercent()).isEqualTo(73);
        assertThat(s.getStorageQuotaPercent()).isEqualTo(80);
        assertThat(s.getStorageBytesFree()).isEqualTo(200_000L);
    }

    @Test
    void llmPowerOff_isReflected() {
        when(llmPowerService.load(TENANT))
                .thenReturn(new LlmPowerState(new LlmPowerState.Switch(null, "admin"), Map.of()));
        when(llmBudgetService.status(eq(TENANT), eq(null))).thenReturn(List.of());
        when(storageQuotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 0L, 0L, -1, false, false));

        HomeStatusDto s = controller.status(request, TENANT, null);

        assertThat(s.getLlmPowerOn()).isFalse();
    }

    @Test
    void noLlmBudget_returnsMinusOne() {
        when(llmPowerService.load(TENANT)).thenReturn(LlmPowerState.ALL_ON);
        when(llmBudgetService.status(eq(TENANT), eq(null))).thenReturn(List.of(tenantScopeWithoutWindows()));
        when(storageQuotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 100L, 0L, -1, false, false));

        HomeStatusDto s = controller.status(request, TENANT, null);

        // Unlimited budget must read as -1 (no budget), not as 0.
        assertThat(s.getLlmBudgetPercent()).isEqualTo(-1);
    }

    @Test
    void noStorageQuota_returnsMinusOneAndNoBytesFree() {
        when(llmPowerService.load(TENANT)).thenReturn(LlmPowerState.ALL_ON);
        when(llmBudgetService.status(eq(TENANT), eq(null))).thenReturn(List.of());
        when(storageQuotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 123L, 0L, -1, false, false));

        HomeStatusDto s = controller.status(request, TENANT, null);

        assertThat(s.getStorageQuotaPercent()).isEqualTo(-1);
        // bytesFree stays null: unlimited → no second line on the tile
        assertThat(s.getStorageBytesFree()).isNull();
    }

    @Test
    void llmPowerFails_leavesFieldNull() {
        when(llmPowerService.load(TENANT)).thenThrow(new RuntimeException("mongo down"));
        when(llmBudgetService.status(eq(TENANT), eq(null))).thenReturn(List.of(tenantScope(20.0)));
        when(storageQuotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 50L, 100L, 50, false, false));

        HomeStatusDto s = controller.status(request, TENANT, null);

        assertThat(s.getLlmPowerOn()).isNull();
        assertThat(s.getLlmBudgetPercent()).isEqualTo(20);
        assertThat(s.getStorageQuotaPercent()).isEqualTo(50);
    }

    @Test
    void llmBudgetFails_leavesFieldNull() {
        when(llmPowerService.load(TENANT)).thenReturn(LlmPowerState.ALL_ON);
        when(llmBudgetService.status(eq(TENANT), eq(null))).thenThrow(new RuntimeException("ledger blip"));
        when(storageQuotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 50L, 100L, 50, false, false));

        HomeStatusDto s = controller.status(request, TENANT, null);

        assertThat(s.getLlmPowerOn()).isTrue();
        assertThat(s.getLlmBudgetPercent()).isNull();
        assertThat(s.getStorageQuotaPercent()).isEqualTo(50);
    }

    @Test
    void storageFails_leavesFieldNull() {
        when(llmPowerService.load(TENANT)).thenReturn(LlmPowerState.ALL_ON);
        when(llmBudgetService.status(eq(TENANT), eq(null))).thenReturn(List.of(tenantScope(20.0)));
        when(storageQuotaService.status(TENANT)).thenThrow(new RuntimeException("aggregation blip"));

        HomeStatusDto s = controller.status(request, TENANT, null);

        assertThat(s.getStorageQuotaPercent()).isNull();
        assertThat(s.getStorageBytesFree()).isNull();
    }

    @Test
    void multipleWindows_takesTheHighestPercent() {
        // The endpoint picks the most-loaded active window so a daily spike
        // surfaces even when the monthly window is still mostly empty.
        when(llmPowerService.load(TENANT)).thenReturn(LlmPowerState.ALL_ON);
        when(llmBudgetService.status(eq(TENANT), eq(null))).thenReturn(List.of(tenantScope(12.0, 87.0, 45.0)));
        when(storageQuotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 0L, 0L, -1, false, false));

        HomeStatusDto s = controller.status(request, TENANT, null);

        assertThat(s.getLlmBudgetPercent()).isEqualTo(87);
    }

    @Test
    void projectBudgetDoesNotBleedIntoTenantStatus() {
        // The endpoint filters to TENANT — a PROJECT scope must not affect
        // the landing page. The mock service still returns the project entry
        // because it filters by tenantId; the controller must ignore it.
        LlmBudgetService.ScopeStatus tenant = tenantScope(40.0);
        LlmBudgetService.ScopeStatus project =
                new LlmBudgetService.ScopeStatus(BudgetScope.PROJECT, "research", LlmBudget.NONE, List.of());
        when(llmPowerService.load(TENANT)).thenReturn(LlmPowerState.ALL_ON);
        when(llmBudgetService.status(eq(TENANT), eq(null))).thenReturn(List.of(project, tenant));
        when(storageQuotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 0L, 0L, -1, false, false));

        HomeStatusDto s = controller.status(request, TENANT, null);

        assertThat(s.getLlmBudgetPercent()).isEqualTo(40);
        assertThat(s.getLlmBudgetScope()).isEqualTo("_tenant");
    }

    @Test
    void projectIdParameter_readsProjectScope() {
        // ?projectId=X flips the LLM-budget read to the PROJECT scope, so the
        // session picker can show the user's own project budget instead of
        // the tenant's. The mock service returns the same project entry
        // either way; what matters is that the controller passes the
        // projectId to the service and filters by PROJECT scope.
        LlmBudgetService.ScopeStatus project = new LlmBudgetService.ScopeStatus(
                BudgetScope.PROJECT, "research", LlmBudget.NONE, List.of(window(22.0), window(65.0)));
        LlmBudgetService.ScopeStatus tenant = tenantScope(7.0);
        when(llmPowerService.load(TENANT)).thenReturn(LlmPowerState.ALL_ON);
        when(llmBudgetService.status(eq(TENANT), eq("research"))).thenReturn(List.of(project, tenant));
        when(storageQuotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 0L, 0L, -1, false, false));

        HomeStatusDto s = controller.status(request, TENANT, "research");

        assertThat(s.getLlmBudgetPercent()).isEqualTo(65);
        assertThat(s.getLlmBudgetScope()).isEqualTo("research");
        // Storage stays tenant-only regardless of projectId.
        assertThat(s.getStorageQuotaPercent()).isEqualTo(-1);
    }

    @Test
    void projectIdUnreadable_leavesBudgetEmpty_andKeepsTenantFields() {
        when(authority.check(eq(request), eq(new Resource.Project(TENANT, "secret")), eq(Action.READ)))
                .thenReturn(false);
        when(llmPowerService.load(TENANT)).thenReturn(LlmPowerState.ALL_ON);
        when(storageQuotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 800_000L, 1_000_000L, 80, false, true));

        HomeStatusDto s = controller.status(request, TENANT, "secret");

        assertThat(s.getLlmBudgetPercent()).isNull();
        assertThat(s.getLlmBudgetScope()).isNull();
        assertThat(s.getLlmPowerOn()).isTrue();
        assertThat(s.getStorageQuotaPercent()).isEqualTo(80);
        verify(llmBudgetService, never()).status(any(), any());
    }

    @Test
    void storageUsageUnknown_leavesStorageFieldsEmpty() {
        when(storageQuotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, -1L, 1_000_000L, 0, false, false));

        HomeStatusDto s = controller.status(request, TENANT, null);

        assertThat(s.getStorageQuotaPercent()).isNull();
        assertThat(s.getStorageBytesFree()).isNull();
    }

    @Test
    void projectIdBlank_isTreatedAsNoProject() {
        // A stray "?projectId=" (empty value) must not crash and must
        // fall back to the tenant scope, like the parameter were absent.
        when(llmPowerService.load(TENANT)).thenReturn(LlmPowerState.ALL_ON);
        when(llmBudgetService.status(eq(TENANT), eq(null))).thenReturn(List.of(tenantScope(50.0)));
        when(storageQuotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 0L, 0L, -1, false, false));

        HomeStatusDto s = controller.status(request, TENANT, "");

        assertThat(s.getLlmBudgetPercent()).isEqualTo(50);
        assertThat(s.getLlmBudgetScope()).isEqualTo("_tenant");
    }

    // ──────────────────── helpers ────────────────────

    private static LlmBudgetService.ScopeStatus tenantScope(double... percents) {
        List<LlmBudgetService.WindowStatus> windows = new java.util.ArrayList<>();
        for (double p : percents) {
            windows.add(window(p));
        }
        return new LlmBudgetService.ScopeStatus(BudgetScope.TENANT, "_tenant", LlmBudget.NONE, windows);
    }

    private static LlmBudgetService.ScopeStatus tenantScopeWithoutWindows() {
        return new LlmBudgetService.ScopeStatus(BudgetScope.TENANT, "_tenant", LlmBudget.NONE, List.of());
    }

    private static LlmBudgetService.WindowStatus window(double percent) {
        // Only `percent` matters for these tests — other fields stay at defaults.
        return new LlmBudgetService.WindowStatus(
                BudgetScope.TENANT,
                "_tenant",
                BudgetWindow.DAILY,
                java.time.LocalDate.parse("2026-10-19"),
                1_000_000L,
                (long) (percent * 10_000),
                "USD",
                80,
                0L,
                java.util.Map.of());
    }
}
