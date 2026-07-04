package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Flat wire DTO for a canvas node (the sealed {@code CanvasNode} model
 * projected to a single TS-friendly shape). Type-specific fields are
 * nullable; {@code type} is the discriminator.
 */
@GenerateTypeScript("canvas")
public record CanvasNodeDto(
        String id,
        String type,
        double x,
        double y,
        double w,
        double h,
        @Nullable String color,
        @Nullable Integer z,
        @Nullable String parent,
        @Nullable String text,
        @Nullable String ref,
        @Nullable String href,
        @Nullable String title,
        @Nullable String label,
        @Nullable Boolean bold,
        @Nullable Boolean italic,
        @Nullable String fontSize,
        @Nullable String textColor,
        @Nullable String author) {}
