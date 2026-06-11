package de.mhus.vance.brain.image;

import java.awt.Color;
import org.jspecify.annotations.Nullable;

/**
 * Hex-string → {@link java.awt.Color} parser used by the resize-contain
 * and rotate background-fill flows. Accepts {@code #rrggbb} and
 * {@code #aarrggbb}; rejects everything else with a
 * {@link ImageManipulationException.Reason#PARAMETER_INVALID}.
 */
final class ColorParser {

    private ColorParser() {}

    /**
     * @param hex caller-supplied colour, e.g. {@code #ffffff} or
     *            {@code #80000000} (semi-transparent black).
     * @param fallback used when {@code hex} is {@code null} or blank.
     */
    static Color parseOrDefault(@Nullable String hex, Color fallback) {
        if (hex == null || hex.isBlank()) {
            return fallback;
        }
        String s = hex.trim();
        if (!s.startsWith("#")) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "background must be a hex colour starting with '#', got '" + hex + "'");
        }
        String body = s.substring(1);
        try {
            return switch (body.length()) {
                case 6 -> new Color(Integer.parseInt(body, 16), false);
                case 8 -> new Color((int) Long.parseLong(body, 16), true);
                default -> throw new ImageManipulationException(
                        ImageManipulationException.Reason.PARAMETER_INVALID,
                        "background must be #rrggbb or #aarrggbb, got '" + hex + "'");
            };
        } catch (NumberFormatException e) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "background '" + hex + "' is not a valid hex colour");
        }
    }
}
