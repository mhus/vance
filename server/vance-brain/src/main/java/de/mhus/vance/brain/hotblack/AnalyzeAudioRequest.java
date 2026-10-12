package de.mhus.vance.brain.hotblack;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link HotblackService#analyze(AnalyzeAudioRequest)}.
 * Exactly one audio source must be present: a {@code documentId} in
 * the caller's project (the tool path — resolved through the
 * attachment pipeline) or raw {@code audioData} (internal callers).
 * The {@code prompt} is the caller's question about the audio; the
 * answer language defaults to the {@code chat.language} cascade.
 */
@Value
@Builder
public class AnalyzeAudioRequest {

    /** Required. Tenant scope. */
    String tenantId;

    /** Optional. Username of the caller. */
    @Nullable
    String userId;

    /** Optional. Project scope. */
    @Nullable
    String projectId;

    /** Optional. Process scope — setting cascade + progress side-channel. */
    @Nullable
    String processId;

    /** Audio document to analyze (tool path). */
    @Nullable
    String documentId;

    /** Raw audio bytes (internal callers). */
    byte @Nullable [] audioData;

    /** Wire format of {@link #audioData} ({@code wav}, {@code mp3}, …). */
    @Nullable
    String audioFormat;

    /** Display name for logs / error messages. */
    @Nullable
    String audioName;

    /** Required. The question about the audio ("describe genre, tempo, mood"). */
    String prompt;

    /** Optional ISO-639-1 answer language. {@code null} ⇒ {@code chat.language} cascade. */
    @Nullable
    String language;

    /** Optional document path for the analysis text document. */
    @Nullable
    String path;

    /** Model alias to resolve. {@code null} ⇒ {@code default:au}. */
    @Nullable
    String alias;
}
