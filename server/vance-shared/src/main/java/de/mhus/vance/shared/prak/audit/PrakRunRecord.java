package de.mhus.vance.shared.prak.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One row per successful Prak analyzer pass.
 *
 * <p>The fields below are deliberately flat — they cover the four
 * Prak stages (analyzer → sanitizer → strength-deriver →
 * promotion-service) plus telemetry (model, duration, trigger). The
 * collection {@code prak_runs} is the audit-trail backing
 * "show me everything Prak did for tenant X over the last week".
 *
 * <p>Failed runs (analyzer throws, schema-loop exhausted, …) do
 * <em>not</em> produce a record — those are visible only via the
 * {@code vance.prak.sideChannel{outcome=error}} counter and the
 * warn-level log line. Future work may add a failure-aware record
 * shape.
 */
@Document(collection = "prak_runs")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_project_time_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'createdAt': -1 }"),
        @CompoundIndex(
                name = "runId_idx",
                def = "{ 'runId': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrakRunRecord {

    @Id
    private @Nullable String id;

    private String tenantId = "";
    private String projectId = "";
    private @Nullable String sessionId;
    private @Nullable String processId;

    /** Caller-supplied correlation token (matches {@code PromotionContext.runId}). */
    private String runId = "";

    /**
     * Which trigger fired this run — {@code compaction-side-channel:sliding},
     * {@code compaction-side-channel:range:<topic>}, {@code hot-path:<marker>},
     * {@code autodream}, {@code background-consistency}.
     */
    private String trigger = "";

    private @Nullable String windowFromTurnId;
    private @Nullable String windowToTurnId;
    private int windowMessages;

    // ── Sanitizer ──
    private int rawItemCount;
    private int finalItemCount;
    private int droppedNoEvidence;
    private int droppedLowConfidence;
    private int droppedBySupersedeWithinBatch;
    private int duplicatesMerged;
    private int confidencePenalised;
    private boolean hardCapTriggered;
    private double evidenceCoverage;
    private boolean lowCoverage;

    // ── Strength-Deriver ──
    private int strengthOverrides;
    private long strengthTagsModified;

    // ── Promotion ──
    private int promoted;
    private int inboxOffered;
    private int skipped;
    private int refreshed;
    private int affectsResolved;
    private int affectsDeferred;

    @Builder.Default
    private List<String> persistedMemoryIds = new ArrayList<>();

    // ── Telemetry ──
    private @Nullable String model;
    private long durationMs;

    @CreatedDate
    private @Nullable Instant createdAt;
}
