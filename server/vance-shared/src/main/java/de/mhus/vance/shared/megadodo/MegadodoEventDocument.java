package de.mhus.vance.shared.megadodo;

import de.mhus.vance.api.megadodo.MegadodoPhase;
import de.mhus.vance.api.megadodo.MegadodoRefType;
import de.mhus.vance.api.megadodo.MegadodoSeverity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One row of the project activity feed. Append-only — producers create,
 * the UI reads, nothing ever updates a row.
 *
 * <p>Retention runs through {@link #expiresAt} plus a Mongo TTL index, not
 * through a scheduled prune job. A job that has to be running for the data
 * to stay bounded eventually is not running; the predecessor
 * {@code EventLogService.deleteOlderThan} had no caller at all.
 * {@code expiresAt == null} means keep forever — Mongo's TTL monitor skips
 * documents whose indexed field is absent.
 *
 * <p>See {@code specification/public/megadodo-system.md}.
 */
@Document(collection = "megadodo_events")
@CompoundIndexes({
        // The paging sort is (timestamp desc, _id desc) — the tie-break has to
        // be in the index or the planner cannot satisfy the sort from it and
        // adds a blocking SORT stage over the whole match, which walks into the
        // 32 MB in-memory sort limit on a busy project. That failure would land
        // on the *read* side, where nothing swallows it.
        @CompoundIndex(name = "feed_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'timestamp': -1, '_id': -1 }"),
        // Same query without a projectId — the tenant-wide feed. It cannot use
        // the index above at all: with projectId absent from the filter,
        // timestamp is no longer the key immediately after the equality prefix.
        @CompoundIndex(name = "feed_tenant_idx",
                def = "{ 'tenantId': 1, 'timestamp': -1, '_id': -1 }"),
        @CompoundIndex(name = "trace_idx",
                def = "{ 'tenantId': 1, 'traceId': 1, 'projectId': 1, 'timestamp': 1 }"),
        @CompoundIndex(name = "ref_idx",
                def = "{ 'tenantId': 1, 'refType': 1, 'refId': 1, 'timestamp': -1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MegadodoEventDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** {@code null} for tenant-wide rows — "user created" is not a project event. */
    private @Nullable String projectId;

    private Instant timestamp = Instant.EPOCH;

    /** Dotted, lowercase: {@code scheduler.run}, {@code session.lifecycle}. */
    private String action = "";

    private MegadodoPhase phase = MegadodoPhase.SINGLE;

    private MegadodoSeverity severity = MegadodoSeverity.INFO;

    /** {@code success} | {@code failure} | {@code skipped}; {@code null} on START. */
    private @Nullable String outcome;

    /**
     * Groups the rows of one operation. Reuses an id that already exists
     * — a scheduler run's {@code correlationId}, a session's id — rather
     * than minting a parallel one.
     */
    private String traceId = "";

    private @Nullable String actor;

    private @Nullable MegadodoRefType refType;

    private @Nullable String refId;

    private @Nullable String message;

    /** Project-relative path of the detailed run log, when one exists. */
    private @Nullable String logPath;

    @Builder.Default
    private Map<String, Object> details = new LinkedHashMap<>();

    /** TTL anchor. {@code null} = keep forever. */
    @Indexed(name = "megadodo_ttl_idx", expireAfterSeconds = 0)
    private @Nullable Instant expiresAt;
}
