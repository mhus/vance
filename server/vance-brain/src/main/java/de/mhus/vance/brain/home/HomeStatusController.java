package de.mhus.vance.brain.home;

import de.mhus.vance.api.home.HomeStatusDto;
import de.mhus.vance.shared.llmusage.BudgetScope;
import de.mhus.vance.shared.llmusage.LlmBudgetService;
import de.mhus.vance.shared.llmusage.LlmPowerService;
import de.mhus.vance.shared.llmusage.LlmPowerState;
import de.mhus.vance.shared.storage.StorageQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only, no-auth landing-page status. The SPA hits this once when the
 * home view mounts, to render the small status hints next to the
 * "Chat" and "Dokumente" tiles.
 *
 * <p>Endpoint: {@code GET /brain/{tenant}/home/status}. No project
 * parameter — the landing has no project, and project budgets must not
 * bleed into it (per {@code planning/storage-quota.md} and the
 * {@code llm-resource-management.md} §5a reasoning that keeps the
 * tenant level separate).
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

    @GetMapping("/status")
    public HomeStatusDto status(@PathVariable("tenant") String tenant) {
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

        // Tenant LLM cost budget — pick the most-loaded active window. A
        // tenant without any limit reports percent = -1 so the SPA can
        // distinguish "no budget" from "could not answer".
        try {
            int percent = llmBudgetService.status(tenant, null).stream()
                    .filter(s -> s.scope() == BudgetScope.TENANT)
                    .findFirst()
                    .map(s -> highestWindowPercent(s))
                    .orElse(-1);
            b.llmBudgetPercent(percent);
        } catch (RuntimeException e) {
            log.warn("LLM budget status of tenant '{}' not readable: {}", tenant, e.toString());
        }

        // Storage quota — same shape as the pre-check, but for the home
        // status. An unlimited quota reports percent = -1; an aggregation
        // failure leaves the field null.
        try {
            StorageQuotaService.Status s = storageQuotaService.status(tenant);
            if (s.limitBytes() > 0) {
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
