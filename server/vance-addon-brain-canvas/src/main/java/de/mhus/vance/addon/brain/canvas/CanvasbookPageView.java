package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one canvas page inside a canvasbook. */
@GenerateTypeScript("canvas")
public record CanvasbookPageView(
        String id,
        String path,
        String relativePath,
        String title,
        @Nullable String description) {}
