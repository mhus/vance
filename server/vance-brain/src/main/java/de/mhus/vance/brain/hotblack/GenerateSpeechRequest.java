package de.mhus.vance.brain.hotblack;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/** Input for {@link HotblackService#speak(GenerateSpeechRequest)}. */
@Value
@Builder
public class GenerateSpeechRequest {

    /** Required. Tenant scope. */
    String tenantId;

    /** Optional. Username of the caller — {@code createdBy} on the document. */
    @Nullable
    String userId;

    /** Optional. Project scope. {@code null} ⇒ tenant-system project. */
    @Nullable
    String projectId;

    /** Optional. Process scope — setting cascade + progress side-channel. */
    @Nullable
    String processId;

    /** Required. The text to speak (markdown is stripped by the service). */
    String text;

    /** Optional ISO-639-1 code. {@code null} ⇒ the {@code chat.language} cascade. */
    @Nullable
    String language;

    /** Optional voice id. {@code null} ⇒ {@code ai.hotblack.default-voice}, else model default. */
    @Nullable
    String voice;

    /** Optional document path. {@code null} ⇒ {@code audio/<uuid>-<slug>.<ext>}. */
    @Nullable
    String path;

    /** Optional title override. {@code null} ⇒ the {@code audio-title} LightLlm recipe. */
    @Nullable
    String title;

    /** Optional output format: {@code mp3} (default) or {@code wav}. */
    @Nullable
    String format;

    /** Optional playback speed multiplier. */
    @Nullable
    Double speed;

    /** Model alias to resolve. {@code null} ⇒ {@code default:tts}. */
    @Nullable
    String alias;
}
