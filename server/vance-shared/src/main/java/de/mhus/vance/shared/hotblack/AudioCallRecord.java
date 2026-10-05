package de.mhus.vance.shared.hotblack;

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
 * One persisted entry per Hotblack audio call (TTS, transcription, or
 * music). Drives the daily / monthly quota check in
 * {@code AudioCallTracker} and feeds future admin views.
 *
 * <p>One row per call, not one row per success — quota math has to see
 * attempts (a provider error may still count against the vendor's rate
 * limits). The {@link #outcome} field distinguishes the categories so
 * analytics can split successes from failures.
 */
@Document(collection = "audio_call_records")
@CompoundIndexes({
    @CompoundIndex(name = "audio_tenant_at_idx", def = "{ 'tenantId': 1, 'at': -1 }"),
    @CompoundIndex(name = "audio_tenant_user_at_idx", def = "{ 'tenantId': 1, 'accountId': 1, 'at': -1 }"),
    @CompoundIndex(name = "audio_tenant_project_at_idx", def = "{ 'tenantId': 1, 'projectId': 1, 'at': -1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioCallRecord {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** Username of the caller; mirrors {@code UserDocument.name}. */
    private @Nullable String accountId;

    /** Project name (mirrors {@code ProjectDocument.name}); {@code null}
     *  for calls made outside a project context. */
    private @Nullable String projectId;

    /**
     * Think-process id of the caller, when there was one — per-process
     * attribution for the audit view. {@code null} for calls made outside
     * a process context.
     */
    private @Nullable String processId;

    /** Modality bucket — {@code tts}, {@code stt}, {@code music}. */
    private String modality = "";

    /** Resolved {@code <provider>:<modelName>} the call ran against. */
    private String modelUsed = "";

    /** Alias label as the caller requested it (e.g. {@code default:tts}). */
    private @Nullable String alias;

    /** USD cost of the call — vendor-reported ({@code usage.cost}) or
     *  catalog estimate. {@code null} = unpriced (counted, not billed). */
    private @Nullable Double costUsd;

    /**
     * Billable input units — characters for TTS, seconds of audio for
     * STT, seconds of generated audio for music. {@code 0} when the
     * provider reports nothing.
     */
    private long inputUnits;

    /**
     * Outcome bucket — {@code success}, {@code timeout},
     * {@code provider_error}, {@code quota_exceeded},
     * {@code content_policy}, {@code cancelled}, {@code pending}.
     * Fixed vocabulary so Prometheus / aggregate views stay
     * low-cardinality.
     */
    private String outcome = "";

    /** Wall-clock time of the call's start. */
    private Instant at = Instant.EPOCH;

    /** Total duration in ms, including network + provider compute. */
    private long durationMs;
}
