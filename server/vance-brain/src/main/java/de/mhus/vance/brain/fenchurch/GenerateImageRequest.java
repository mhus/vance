package de.mhus.vance.brain.fenchurch;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for a {@link FenchurchService#generate(GenerateImageRequest)}
 * call. Built via {@link #builder()}.
 *
 * <p>The four scope-id fields ({@code tenantId} required, the rest
 * optional) drive the style cascade, the API-key lookup, the quota
 * check, and the {@code PROCESS_PROGRESS}-channel updates.
 */
@Value
@Builder
public class GenerateImageRequest {

    /** Required. Tenant scope. */
    String tenantId;

    /** Optional. Username of the caller — used for the user-scope
     *  style layer and as the {@code createdBy} field on the document. */
    @Nullable String userId;

    /** Optional. Project scope. {@code null} ⇒ tenant-system project. */
    @Nullable String projectId;

    /** Optional. Process scope — used for both setting cascades and
     *  the progress side-channel. {@code null} disables heartbeat. */
    @Nullable String processId;

    /** Required. The image-generation prompt. */
    String prompt;

    /**
     * Optional explicit document path. {@code null} (the default)
     * routes the image to {@code images/<uuid>-<slug>.png} where the
     * slug is generated via the {@code image-title} LightLlm recipe.
     */
    @Nullable String path;

    /**
     * Optional title override. {@code null} (the default) routes
     * through the {@code image-title} LightLlm recipe.
     */
    @Nullable String title;

    /**
     * Optional aspect-ratio. {@code null} ⇒ the cascade default from
     * {@code ai.fenchurch.default_aspect_ratio} or hard-default
     * {@code 1:1}.
     */
    @Nullable String aspectRatio;

    /**
     * Model alias to resolve. {@code null} ⇒ {@code default:image}.
     * Use {@code default:image-high} to request the quality tier.
     */
    @Nullable String alias;
}
