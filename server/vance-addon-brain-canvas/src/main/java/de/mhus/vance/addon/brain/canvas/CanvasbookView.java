package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO for a canvasbook scan — the app manifest view + page list. */
@GenerateTypeScript("canvas")
public record CanvasbookView(
        String folder,
        @Nullable String title,
        @Nullable String description,
        @Nullable String landingPagePath,
        @Nullable String landingPageId,
        List<CanvasbookPageView> pages) {}
