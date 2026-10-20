package de.mhus.vance.api.cleanup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Bytes-of-storage breakdown of one scope (the whole tenant or one project),
 * served by {@code GET /brain/{tenant}/storage/breakdown}. Powers the "free up
 * space" actions in Insights → Quotas: a tenant admin who is at the quota
 * limit needs to see which pool is eating the bytes before picking the right
 * cleanup action.
 *
 * <p>The four byte counts add up:
 * <ul>
 *   <li>{@link #usedBytes} — every byte that counts against the quota,
 *       whatever lives behind it.</li>
 *   <li>{@link #trashBytes} — bytes still bound to documents in the project's
 *       {@code _vance/trash/} folder. Releasing these is a single admin click:
 *       hard-delete every entry.</li>
 *   <li>{@link #archiveBytes} — bytes bound to archived document versions
 *       ({@code document_archives}). Releasing these needs a "keep latest N"
 *       decision per lineage.</li>
 *   <li>{@link #liveBytes} — what is left after the two above; the working
 *       set. Cannot be reclaimed without losing work.</li>
 * </ul>
 *
 * <p>{@link #reclaimableBytes} is {@code trashBytes + archiveBytes} — the
 * upper bound for what the two cleanup actions can return. The UI uses it as
 * the meter of "is this cleanup worth running".
 *
 * <p>{@link #limitBytes} and {@link #percentUsed} mirror the
 * {@code storage_quota} status endpoint and are returned alongside so the UI
 * can refresh its quota header in the same roundtrip.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("cleanup")
public class StorageBreakdownDto {

    /** Tenant the breakdown belongs to. */
    private String tenant = "";

    /** {@code null} for tenant-wide, the project name for a project-scoped call. */
    private @Nullable String projectId;

    /** Every byte that counts against the quota — live + trash + archives. */
    private long usedBytes;

    /** Bytes bound to documents under {@code _vance/trash/}. One hard-delete per entry releases them. */
    private long trashBytes;

    /** Bytes bound to archived document versions (the {@code document_archives} collection). */
    private long archiveBytes;

    /** {@code usedBytes − trashBytes} — the working set; the bytes that cannot be reclaimed by cleanup. */
    private long liveBytes;

    /** {@code trashBytes + archiveBytes} — what the cleanup actions can return at best. */
    private long reclaimableBytes;

    /** Number of documents currently in {@code _vance/trash/}. */
    private long trashCount;

    /** Number of archive entries in {@code document_archives}. */
    private long archiveCount;

    /**
     * Top-N individual archives by size, lineage-grouped. The admin sees the
     * five biggest version histories in one glance — usually the place where
     * the bulk of {@link #archiveBytes} sits. Empty when the scope has no
     * archives.
     */
    @Builder.Default
    private List<ArchiveWeightDto> topArchives = new ArrayList<>();

    /** Quota limit for this tenant, or {@code -1} when no limit is configured. */
    private long limitBytes;

    /**
     * Percent of {@link #limitBytes} currently consumed. {@code -1} when no
     * limit is configured; {@code null} is not used here — the breakdown is
     * an admin surface and the answer must always be present.
     */
    private int percentUsed;

    /** Wall-clock of the aggregation result; lets the UI say "X minutes ago". */
    private @Nullable Instant computedAt;
}
