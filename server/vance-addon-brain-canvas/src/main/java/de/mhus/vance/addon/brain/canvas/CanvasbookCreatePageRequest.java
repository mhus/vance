package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Request body to create a new canvas page inside a canvasbook. */
@GenerateTypeScript("canvas")
public record CanvasbookCreatePageRequest(
        @Nullable String title,
        @Nullable String slug,
        @Nullable String description) {}
