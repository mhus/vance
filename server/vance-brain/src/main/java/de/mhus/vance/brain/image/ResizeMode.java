package de.mhus.vance.brain.image;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Resize strategy for {@link ResizeRequest}. See
 * {@code specification/image-manipulation.md} §4.2.
 */
public enum ResizeMode {
    /** Force exact {@code width × height} — may distort the aspect ratio. */
    EXACT,
    /** Scale to target {@code width}; height proportional. */
    WIDTH,
    /** Scale to target {@code height}; width proportional. */
    HEIGHT,
    /** Fill the {@code width × height} box and crop excess (no padding). */
    COVER,
    /** Fit inside the {@code width × height} box, pad with {@code background}. */
    CONTAIN;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ResizeMode fromWire(@Nullable String s) {
        if (s == null || s.isBlank()) return EXACT;
        try {
            return ResizeMode.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "Unknown resize mode '" + s + "'. Expected: exact, width, height, cover, contain.");
        }
    }
}
