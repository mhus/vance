package de.mhus.vance.addon.brain.scribble.model;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One committed ink stroke: tool, palette color index, pen width and the
 * simplified point list. Strokes carry no ids — they are an ordered array;
 * erasing and undo are client-side array operations (see
 * {@code planning/scribble.md} §2.6).
 */
public record ScribbleStroke(Tool tool, String color, Width width, List<ScribblePoint> points) {

    public ScribbleStroke {
        points = List.copyOf(points);
    }

    /**
     * Ink tool. v1 ships only {@code PEN}; highlighter and friends join
     * here later — the wire value is the lower-case name, the format
     * does not change.
     */
    public enum Tool {
        PEN;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Tool parse(@Nullable String s, Tool fallback) {
            if (s == null || s.isBlank()) return fallback;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "pen" -> PEN;
                default -> fallback;
            };
        }
    }

    /** Pen size — three fixed values, deliberately no slider. */
    public enum Width {
        S,
        M,
        L;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Width parse(@Nullable String s, Width fallback) {
            if (s == null || s.isBlank()) return fallback;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "s" -> S;
                case "l" -> L;
                case "m" -> M;
                default -> fallback;
            };
        }
    }
}
