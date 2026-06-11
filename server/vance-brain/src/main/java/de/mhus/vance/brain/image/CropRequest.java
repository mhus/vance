package de.mhus.vance.brain.image;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link ImageManipulationService#crop(CropRequest)}. Source
 * document is loaded via {@code path} in the caller's tenant/project
 * scope; the cropped result is written back to {@code targetPath} (if
 * set and distinct from {@code path}) or overwrites {@code path}, in
 * which case document-versioning archives the prior version.
 */
@Value
@Builder
public class CropRequest {

    /** Required. Tenant scope. */
    String tenantId;

    /** Optional. Username of the caller — passed through to the
     *  document write as {@code createdBy}. */
    @Nullable String userId;

    /** Optional. Project scope. {@code null} ⇒ tenant-system project. */
    @Nullable String projectId;

    /** Optional. Process scope — used for the setting cascade and the
     *  progress side-channel. */
    @Nullable String processId;

    /** Required. Source document path. */
    String path;

    /** Optional. Destination path. {@code null} or equal to {@code path}
     *  ⇒ overwrite source. */
    @Nullable String targetPath;

    /** Left edge of the crop rectangle, 0-based. */
    int x;

    /** Top edge of the crop rectangle, 0-based. */
    int y;

    /** Crop width in pixels. Must be {@code > 0}. */
    int width;

    /** Crop height in pixels. Must be {@code > 0}. */
    int height;
}
