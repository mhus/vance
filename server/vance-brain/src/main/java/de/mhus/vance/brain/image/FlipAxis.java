package de.mhus.vance.brain.image;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Mirror axis for {@link FlipRequest}.
 *
 * <p>Convention follows photo-editor lingo, not the Scrimage primitive
 * names: {@code horizontal} = mirror left-right (Scrimage {@code flipX}),
 * {@code vertical} = mirror top-bottom (Scrimage {@code flipY}).
 */
public enum FlipAxis {
    HORIZONTAL,
    VERTICAL;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FlipAxis fromWire(@Nullable String s) {
        if (s == null || s.isBlank()) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "'axis' is required: horizontal or vertical");
        }
        try {
            return FlipAxis.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "Unknown flip axis '" + s + "'. Expected: horizontal, vertical.");
        }
    }
}
