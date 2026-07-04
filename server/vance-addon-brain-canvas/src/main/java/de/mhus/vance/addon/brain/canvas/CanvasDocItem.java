package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one project document (picker result / embed resolution). */
@GenerateTypeScript("canvas")
public record CanvasDocItem(
        String id,
        String path,
        @Nullable String title,
        @Nullable String kind,
        @Nullable String mimeType) {}
