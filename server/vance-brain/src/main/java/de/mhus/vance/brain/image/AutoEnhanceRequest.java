package de.mhus.vance.brain.image;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link ImageManipulationService#autoEnhance(AutoEnhanceRequest)}.
 * The algorithm is parameter-free at the API level — tuning lives in the
 * Setting-Cascade ({@code image.tools.auto_enhance.*}, see
 * {@code specification/image-manipulation.md} §5).
 */
@Value
@Builder
public class AutoEnhanceRequest {

    String tenantId;
    @Nullable String userId;
    @Nullable String projectId;
    @Nullable String processId;
    String path;
    @Nullable String targetPath;
}
