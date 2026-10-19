package de.mhus.vance.api.home;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Compact tenant-level status for the landing page. Powers the small "Chat"
 * / "Dokumente" status hints next to the launcher tiles.
 *
 * <p>Three pieces, all optional and independently nullable when the
 * corresponding data could not be answered (auth denied, network error,
 * controller absent on this build). The SPA renders nothing for a null field
 * — a missing value never blocks the launcher.
 *
 * <p>This DTO is deliberately read-only and tenant-scope only: there are no
 * per-project or per-user fields, and no admin actions. The full admin
 * surface stays behind {@code GET /usage/budget} (admin-only) and
 * {@code GET /llm-power} (admin-only) — see {@code llm-resource-management.md}
 * §5a and §5b.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("home")
public class HomeStatusDto {

    /**
     * Whether the tenant-level LLM power switch is currently ON. {@code null}
     * when the answer could not be obtained; the SPA renders nothing in that
     * case so a transient outage never reads as "off".
     */
    @Nullable
    private Boolean llmPowerOn;

    /**
     * Tenant LLM cost budget usage, expressed as a percent of the most
     * loaded active window (daily / monthly / total-with-since). {@code -1}
     * when no budget is configured at the tenant level; {@code null} when the
     * value could not be answered.
     *
     * <p>Only the tenant level is sampled — a per-project budget must not
     * bleed into the landing-page tile, the page has no project.
     */
    @Nullable
    private Integer llmBudgetPercent;

    /**
     * Tenant storage quota usage, percent of {@code storage.quota.maxBytes}.
     * {@code -1} when no quota is configured; {@code null} when the value
     * could not be answered.
     */
    @Nullable
    private Integer storageQuotaPercent;

    /**
     * Bytes still available under the tenant storage quota, or {@code null}
     * when no quota is configured or the value could not be answered. Shown
     * next to {@link #storageQuotaPercent} on the "Dokumente" tile.
     */
    @Nullable
    private Long storageBytesFree;
}
