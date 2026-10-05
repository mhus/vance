package de.mhus.vance.brain.hotblack;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/** Input for {@link HotblackService#compose(GenerateMusicRequest)}. */
@Value
@Builder
public class GenerateMusicRequest {

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

    /** Required. Style / content prompt ("instrumental jazz trio, warm"). */
    String prompt;

    /** Optional lyric language. {@code null} ⇒ {@code chat.language}; {@code "none"} = instrumental. */
    @Nullable
    String language;

    /** Optional target clip length in seconds (a hint to the model). */
    int durationSeconds;

    /** Optional document path. {@code null} ⇒ {@code audio/<uuid>-<slug>.<ext>}. */
    @Nullable
    String path;

    /** Optional title override. {@code null} ⇒ the {@code audio-title} LightLlm recipe. */
    @Nullable
    String title;

    /** Optional output format: {@code mp3} (default) or {@code wav}. */
    @Nullable
    String format;

    /** Model alias to resolve. {@code null} ⇒ {@code default:music}. */
    @Nullable
    String alias;
}
