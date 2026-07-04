package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Response of a canvasbook index rebuild. */
@GenerateTypeScript("canvas")
public record CanvasbookRebuildResponse(
        String folder,
        String indexPath,
        @Nullable String indexLink,
        int pageCount) {}
