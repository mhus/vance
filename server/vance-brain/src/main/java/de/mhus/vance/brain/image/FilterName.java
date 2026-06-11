package de.mhus.vance.brain.image;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Filter dispatcher catalogue for the {@code image_filter} tool. Each
 * enum value corresponds to one Scrimage filter that can be applied with
 * the parameters documented next to the entry in
 * {@code specification/image-manipulation.md} §4.6.
 */
public enum FilterName {
    BLUR_GAUSSIAN,
    SHARPEN,
    GRAYSCALE,
    SEPIA,
    INVERT,
    EDGE,
    EMBOSS,
    POSTERIZE,
    SOLARIZE,
    THRESHOLD;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FilterName fromWire(@Nullable String s) {
        if (s == null || s.isBlank()) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "'filter' is required (e.g. blur_gaussian, sharpen, grayscale)");
        }
        try {
            return FilterName.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "Unknown filter '" + s + "'. Available: blur_gaussian, sharpen, "
                            + "grayscale, sepia, invert, edge, emboss, posterize, "
                            + "solarize, threshold.");
        }
    }
}
