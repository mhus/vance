package de.mhus.vance.brain.home;

import de.mhus.vance.api.home.HomeStatusDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.llmusage.BudgetScope;
import de.mhus.vance.shared.llmusage.LlmBudgetService;
import de.mhus.vance.shared.llmusage.LlmPowerService;
import de.mhus.vance.shared.llmusage.LlmPowerState;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.storage.StorageQuotaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only landing-page status. The SPA hits this once when the home view
 * mounts, to render the small status hints next to the "Chat" and
 * "Dokumente" tiles, and from the session picker for the selected project.
 *
 * <p>Endpoint: {@code GET /brain/{tenant}/home/status}. Any signed-in member
 * of the tenant may read the tenant-level fields — they are percentages, no
 * amounts. With {@code ?projectId=X} the budget is read at the project level,
 * and only when the caller may read that project: otherwise the budget fields
 * stay {@code null}, exactly like an unanswerable value, so the endpoint
 * neither leaks another project's spend nor tells whether it exists.
 *
 * <p>Each field is independently nullable: a failed load on one
 * component (Mongo blip on the storage aggregation, missing brain
 * dependency on a custom build) leaves the other two. The SPA renders
 * nothing for null — the worst case is a tile without a status hint,
 * never a tile with a misleading one. The same fail-open philosophy as
 * {@link LlmBudgetService} {@code check()} and
 * {@link StorageQuotaService} {@code check()} (R1/R5).
 */
@RestController
@RequestMapping("/brain/{tenant}/home")
@RequiredArgsConstructor
@Slf4j
public class HomeStatusController {

    private final LlmPowerService llmPowerService;
    private final LlmBudgetService llmBudgetService;
    private final StorageQuotaService storageQuotaService;
    private final RequestAuthority authority;

    @GetMapping("/status")
    public HomeStatusDto status(
            HttpServletRequest httpRequest,
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId) {
        HomeStatusDto.HomeStatusDtoBuilder b = HomeStatusDto.builder();

        // LLM main switch — read from the store, never from the in-memory
        // cache of LlmPowerSwitch: the cache is brain-only and this endpoint
        // may be the first place a tenant is asked about on a freshly
        // booted pod, so we want the truth from the source.
        try {
            LlmPowerState power = llmPowerService.load(tenant);
            b.llmPowerOn(power.tenantOn());
        } catch (RuntimeException e) {
            log.warn("LLM power state of tenant '{}' not readable: {}", tenant, e.toString());
        }

        // LLM cost budget — tenant by default, project when ?projectId=X and
        // the caller may read that project.
        // Pick the most-loaded active window; -1 when no limit is set on
        // the queried scope (distinct from "could not answer", which
        // leaves the field null). The scope label travels with the
        // percent so the SPA can show "Tenant" vs "Project" without
        // tracking the request itself.
        try {
            BudgetScope scope;
            String scopeLabel;
            String serviceProjectId;
            if (projectId != null && !projectId.isBlank()) {
                if (!authority.check(httpRequest, new Resource.Project(tenant, projectId), Action.READ)) {
                    return finish(b, tenant);
                }
                scope = BudgetScope.PROJECT;
                scopeLabel = projectId;
                serviceProjectId = projectId;
            } else {
                scope = BudgetScope.TENANT;
                scopeLabel = "_tenant";
                serviceProjectId = null;
            }
            int percent = llmBudgetService.status(tenant, serviceProjectId).stream()
                    .filter(s -> s.scope() == scope)
                    .findFirst()
                    .map(s -> highestWindowPercent(s))
                    .orElse(-1);
            b.llmBudgetPercent(percent);
            b.llmBudgetScope(scopeLabel);
        } catch (RuntimeException e) {
            log.warn("LLM budget status of '{}/{}' not readable: {}", tenant, projectId, e.toString());
        }
        return finish(b, tenant);
    }

    /** Adds the tenant-only storage fields and builds the answer. */
    private HomeStatusDto finish(HomeStatusDto.HomeStatusDtoBuilder b, String tenant) {
        // Storage quota — tenant-only. There is no per-project storage
        // quota (per planning/storage-quota.md), so a projectId here is
        // intentionally ignored for the storage fields.
        try {
            StorageQuotaService.Status s = storageQuotaService.status(tenant);
            if (s.limitBytes() > 0 && s.usedBytes() < 0) {
                // Limit known, usage not (aggregation failed): unanswerable,
                // so both fields stay null rather than claiming an empty store.
                log.trace("Storage usage of tenant '{}' unknown, leaving quota fields empty", tenant);
            } else if (s.limitBytes() > 0) {
                b.storageQuotaPercent(s.percentUsed());
                b.storageBytesFree(Math.max(0L, s.limitBytes() - s.usedBytes()));
            } else {
                b.storageQuotaPercent(-1);
                // bytesFree stays null: unlimited → no second line on the tile
            }
        } catch (RuntimeException e) {
            log.warn("Storage quota status of tenant '{}' not readable: {}", tenant, e.toString());
        }

        return b.build();
    }

    /**
     * Highest {@code percent} of all active windows on the scope. {@code -1}
     * when the scope has no active window (no limit set, or every window
     * is the unset default) — "unlimited" reads as a missing number, not
     * as zero.
     */
    private static int highestWindowPercent(LlmBudgetService.ScopeStatus scope) {
        int worst = -1;
        for (LlmBudgetService.WindowStatus w : scope.windows()) {
            int p = (int) Math.round(w.percent());
            if (p > worst) worst = p;
        }
        return worst;
    }
}
