package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for a directed canvas edge. Ends are {@code "none"|"arrow"}. */
@GenerateTypeScript("canvas")
public record CanvasEdgeDto(
        String id,
        String from,
        String to,
        @Nullable String fromSide,
        @Nullable String toSide,
        String fromEnd,
        String toEnd,
        @Nullable String label,
        @Nullable String color) {}
