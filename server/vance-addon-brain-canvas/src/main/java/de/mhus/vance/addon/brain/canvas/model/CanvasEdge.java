package de.mhus.vance.addon.brain.canvas.model;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A directed connection between two nodes. Direction is expressed via
 * arrow ends ({@code fromEnd}/{@code toEnd}); the v1 default is a simple
 * {@code from → to} arrow (see {@code planning/canvas.md} §4.4).
 */
public record CanvasEdge(
        String id,
        String from,
        String to,
        @Nullable Side fromSide,
        @Nullable Side toSide,
        @Nullable Integer fromSlot,
        @Nullable Integer toSlot,
        End fromEnd,
        End toEnd,
        @Nullable String label,
        @Nullable String color,
        @Nullable Boolean dashed,
        @Nullable Double width) {

    /** Attachment points per node side; slots are indexed {@code 0..SLOTS-1} along the side. */
    public static final int SLOTS = 3;

    /** Slot used when an edge carries none — the middle of the side. */
    public static final int DEFAULT_SLOT = 1;

    /** Which side of a node an edge attaches to. */
    public enum Side {
        TOP,
        RIGHT,
        BOTTOM,
        LEFT;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static @Nullable Side parse(@Nullable String s) {
            if (s == null || s.isBlank()) return null;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "top" -> TOP;
                case "right" -> RIGHT;
                case "bottom" -> BOTTOM;
                case "left" -> LEFT;
                default -> null;
            };
        }
    }

    /** Arrow-head presence at an edge end. */
    public enum End {
        NONE,
        ARROW;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static End parse(@Nullable String s, End fallback) {
            if (s == null || s.isBlank()) return fallback;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "arrow" -> ARROW;
                case "none" -> NONE;
                default -> fallback;
            };
        }
    }
}
