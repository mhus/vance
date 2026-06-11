package de.mhus.vance.brain.image;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link ImageManipulationService#adjust(AdjustRequest)}.
 * Each parameter is optional — only the explicitly set fields are
 * applied. At least one must be set, otherwise the call is rejected
 * with {@link ImageManipulationException.Reason#PARAMETER_INVALID}.
 *
 * <p>Apply order is fixed: {@code gamma → brightness → contrast →
 * saturation} (see {@code specification/image-manipulation.md} §4.5).
 */
@Value
@Builder
public class AdjustRequest {

    String tenantId;
    @Nullable String userId;
    @Nullable String projectId;
    @Nullable String processId;
    String path;
    @Nullable String targetPath;

    /** {@code -1.0 … +1.0}. {@code 0} = neutral. */
    @Nullable Double brightness;

    /** {@code -1.0 … +1.0}. {@code 0} = neutral. */
    @Nullable Double contrast;

    /** {@code -1.0 … +1.0}. {@code -1} = grayscale, {@code 0} = neutral. */
    @Nullable Double saturation;

    /** {@code 0.1 … 5.0}. {@code 1.0} = neutral. Values below 1 brighten the
     *  midtones, above 1 darken them. */
    @Nullable Double gamma;
}
