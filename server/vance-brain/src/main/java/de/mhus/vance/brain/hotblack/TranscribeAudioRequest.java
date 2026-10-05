package de.mhus.vance.brain.hotblack;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link HotblackService#transcribe(TranscribeAudioRequest)}.
 * Exactly one audio source must be present: a {@code documentId} in the
 * caller's project (the tool path — resolved through the attachment
 * pipeline) or raw {@code audioData} (internal callers such as the
 * {@code video_transcript} ASR fallback with a local file).
 */
@Value
@Builder
public class TranscribeAudioRequest {

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

    /** Audio document to transcribe (tool path). */
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

    /** Optional ISO-639-1 code. {@code null} ⇒ provider auto-detect. */
    @Nullable
    String language;

    /** Optional document path for the transcript text document. */
    @Nullable
    String path;

    /** Model alias to resolve. {@code null} ⇒ {@code default:stt}. */
    @Nullable
    String alias;
}
