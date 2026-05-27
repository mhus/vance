package de.mhus.vance.api.insights;

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
 * Read-only view of one {@code PrakRunRecord} for the insights
 * inspector. Mirrors the persistence document one-to-one — the UI
 * tab renders the fields as a sanitize / strength / promotion
 * breakdown with the runId and duration as the top-line summary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class PrakRunInsightsDto {

    private String id;

    private String runId;

    private String trigger;

    private @Nullable String windowFromTurnId;
    private @Nullable String windowToTurnId;
    private int windowMessages;

    // ── Sanitizer breakdown ──
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

    // ── Strength derivation ──
    private int strengthOverrides;
    private long strengthTagsModified;

    // ── Promotion outcome ──
    private int promoted;
    private int inboxOffered;
    private int skipped;
    private int refreshed;
    private int affectsResolved;
    private int affectsDeferred;

    @Builder.Default
    private List<String> persistedMemoryIds = new ArrayList<>();

    private @Nullable String model;
    private long durationMs;

    private @Nullable Instant createdAt;
}
