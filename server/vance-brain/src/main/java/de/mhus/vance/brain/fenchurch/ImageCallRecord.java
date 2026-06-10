package de.mhus.vance.brain.fenchurch;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One persisted entry per Fenchurch image-generation call. Drives
 * the daily / monthly quota check in {@link ImageCallTracker} and
 * feeds future admin views.
 *
 * <p>One row per call, not one row per success — quota math has to
 * see attempts (provider errors still count against rate limits the
 * vendor imposes, even if not against monetary cost). The
 * {@link #outcome} field distinguishes the categories so analytics
 * can split successes from failures.
 */
@Document(collection = "image_call_records")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_at_idx",
                def = "{ 'tenantId': 1, 'at': -1 }"),
        @CompoundIndex(name = "tenant_user_at_idx",
                def = "{ 'tenantId': 1, 'accountId': 1, 'at': -1 }"),
        @CompoundIndex(name = "tenant_project_at_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'at': -1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageCallRecord {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** Username of the caller; mirrors {@code UserDocument.name}. */
    private @Nullable String accountId;

    /** Project name (mirrors {@code ProjectDocument.name}); {@code null}
     *  for calls made outside a project context. */
    private @Nullable String projectId;

    /** Resolved {@code <provider>:<modelName>} the call ran against. */
    private String modelUsed = "";

    /** Alias label as the caller requested it (e.g. {@code default:image-high}). */
    private @Nullable String alias;

    /** USD cost per image at the call's quality tier — {@code null}
     *  if the {@code ai-models.yaml} entry doesn't list one. */
    private @Nullable Double costUsd;

    /** Quality tier passed to the provider (e.g. {@code standard}, {@code hd}). */
    private @Nullable String qualityTier;

    /**
     * Outcome bucket — {@code success}, {@code timeout},
     * {@code provider_error}, {@code quota_exceeded},
     * {@code content_policy}, {@code cancelled}. Fixed vocabulary so
     * Prometheus / aggregate views stay low-cardinality.
     */
    private String outcome = "";

    /** Wall-clock time of the call's start. */
    private Instant at = Instant.EPOCH;

    /** Total duration in ms, including network + provider compute. */
    private long durationMs;
}
