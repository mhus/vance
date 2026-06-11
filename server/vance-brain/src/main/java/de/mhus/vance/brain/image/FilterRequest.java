package de.mhus.vance.brain.image;

import java.util.Map;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link ImageManipulationService#filter(FilterRequest)}.
 * {@code filter} selects the effect; {@code params} carries the
 * filter-specific knobs (see catalogue in
 * {@code specification/image-manipulation.md} §4.6).
 */
@Value
@Builder
public class FilterRequest {

    String tenantId;
    @Nullable String userId;
    @Nullable String projectId;
    @Nullable String processId;
    String path;
    @Nullable String targetPath;

    FilterName filter;

    @Singular("param")
    Map<String, Object> params;
}
