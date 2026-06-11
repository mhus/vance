package de.mhus.vance.brain.image;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link ImageManipulationService#rotate(RotateRequest)}.
 * Rotation is clockwise by {@code degrees}; any real number is accepted
 * (e.g. {@code 12.5}, {@code -45}).
 */
@Value
@Builder
public class RotateRequest {

    String tenantId;
    @Nullable String userId;
    @Nullable String projectId;
    @Nullable String processId;
    String path;
    @Nullable String targetPath;

    /** Clockwise rotation angle in degrees. */
    double degrees;

    /** Hex color filling the corners exposed by the rotation. Default
     *  transparent for PNG, white for JPEG fallback. */
    @Nullable String background;
}
