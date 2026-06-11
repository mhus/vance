package de.mhus.vance.brain.image;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link ImageManipulationService#resize(ResizeRequest)}.
 * The exact set of required dimension fields depends on {@code mode}:
 * see {@code specification/image-manipulation.md} §4.2.
 */
@Value
@Builder
public class ResizeRequest {

    String tenantId;
    @Nullable String userId;
    @Nullable String projectId;
    @Nullable String processId;
    String path;
    @Nullable String targetPath;

    /** Default {@link ResizeMode#EXACT}. */
    ResizeMode mode;

    /** Pflicht für {@code exact}, {@code width}, {@code cover}, {@code contain}. */
    @Nullable Integer width;

    /** Pflicht für {@code exact}, {@code height}, {@code cover}, {@code contain}. */
    @Nullable Integer height;

    /** Padding color for {@link ResizeMode#CONTAIN}. Hex {@code #rrggbb} /
     *  {@code #aarrggbb}. Default transparent (PNG) / white (JPEG fallback). */
    @Nullable String background;
}
