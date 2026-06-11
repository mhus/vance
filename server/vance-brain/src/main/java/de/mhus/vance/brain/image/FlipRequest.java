package de.mhus.vance.brain.image;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link ImageManipulationService#flip(FlipRequest)}.
 * {@code axis = horizontal} mirrors left-right;
 * {@code axis = vertical} mirrors top-bottom.
 */
@Value
@Builder
public class FlipRequest {

    String tenantId;
    @Nullable String userId;
    @Nullable String projectId;
    @Nullable String processId;
    String path;
    @Nullable String targetPath;

    FlipAxis axis;
}
