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
 * <p>Two kinds of figures:
 * <ul>
 *   <li>{@link #usedBytes} — the tenant's quota counter: every stored byte
 *       of the tenant, whatever lives behind it (documents, archives,
 *       attachments). Always tenant-wide, also on a project-scoped call.</li>
 *   <li>The three pools of the requested scope, summed from the document
 *       sizes:
 *     <ul>
 *       <li>{@link #liveBytes} — active documents outside the trash; the
 *           working set. Cannot be reclaimed without losing work.</li>
 *       <li>{@link #trashBytes} — documents in the {@code _vance/trash/}
 *           folder. Releasing these is a single admin click: hard-delete
 *           every entry.</li>
 *       <li>{@link #archiveBytes} — archived document versions
 *           ({@code document_archives}). Releasing these needs a "keep
 *           latest N" decision per lineage.</li>
 *     </ul></li>
 * </ul>
 * The pools do not add up to {@link #usedBytes}: the quota counter also
 * sees blobs outside the document collections, and a project scope sees
 * only one project's pools.
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

    /** Tenant quota usage in bytes — always tenant-wide; on a tenant without a limit still the real usage. */
    private long usedBytes;

    /** Bytes bound to documents under {@code _vance/trash/}. One hard-delete per entry releases them. */
    private long trashBytes;

    /** Bytes bound to archived document versions (the {@code document_archives} collection). */
    private long archiveBytes;

    /** Active documents of the scope outside the trash — the working set; not reclaimable by cleanup. */
    private long liveBytes;

    /** {@code trashBytes + archiveBytes} — what the cleanup actions can return at best. */
    private long reclaimableBytes;

    /** Number of documents currently in {@code _vance/trash/}. */
    private long trashCount;

    /** Number of archive entries in {@code document_archives}. */
    private long archiveCount;

    /**
     * Top-N archive lineages, one row each, ranked by their largest version.
     * The admin sees the five biggest version histories in one glance — usually the place where
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
