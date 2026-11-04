package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for a directed canvas edge. Ends are {@code "none"|"arrow"}; slots index the attachment point on a side (0..2, absent = middle). */
@GenerateTypeScript("canvas")
public record CanvasEdgeDto(
        String id,
        String from,
        String to,
        @Nullable String fromSide,
        @Nullable String toSide,
        @Nullable Integer fromSlot,
        @Nullable Integer toSlot,
        String fromEnd,
        String toEnd,
        @Nullable String label,
        @Nullable String color,
        @Nullable Boolean dashed,
        @Nullable Double width) {}
