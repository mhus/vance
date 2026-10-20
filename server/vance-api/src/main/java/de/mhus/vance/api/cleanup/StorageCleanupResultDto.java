package de.mhus.vance.api.cleanup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response of one of the {@code /brain/{tenant}/cleanup/*} endpoints. The
 * UI renders "freed N bytes / deleted M entries" and updates the quota
 * meter with the freshly-computed {@link #usedBytes}/{@link #limitBytes}
 * numbers — both came out of the same {@code StorageQuotaService.status()}
 * call, so the admin sees the result without waiting for the cache TTL.
 *
 * <p>{@code skippedDocs} (trash cleanup) reports documents that survived
 * the sweep — usually because the live row's storage blob was already gone
 * or the row was deleted between the iteration cursor and the per-doc
 * delete. A non-zero value means "look at the breakdown again, the
 * numbers are slightly off"; not an error.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("cleanup")
public class StorageCleanupResultDto {

    /** Bytes the action actually released. Sum of {@code size} over the entries it removed. */
    private long freedBytes;

    /** Trash cleanup: documents hard-deleted. Archive cleanup: archive entries deleted. */
    private int deletedEntries;

    /** Per-entry outcomes that did <em>not</em> result in a delete. See class doc. */
    private int skipped;

    /** Echo of the depth parameter so the UI can confirm "kept N versions per document". */
    private int keepLatest;

    /** Quota limit for the tenant, post-cleanup. Mirrors {@code StorageQuotaService.status()}. */
    private long limitBytes;

    /** Tenant quota usage, freshly aggregated post-cleanup. */
    private long usedBytes;

    /** {@code usedBytes / limitBytes} in percent, post-cleanup. {@code -1} when no limit is set. */
    private int percentUsed;

    /**
     * {@code "trash"} or {@code "archives"} — tells the UI which button was
     * pressed when it renders the toast. Clients can also use it to gate
     * "show this action's stats in a sidebar".
     */
    private String action = "";
}
