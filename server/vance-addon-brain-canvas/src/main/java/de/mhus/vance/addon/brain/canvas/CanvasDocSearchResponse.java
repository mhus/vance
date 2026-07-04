package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** Wire DTO for a project-document search (canvas doc-node picker). */
@GenerateTypeScript("canvas")
public record CanvasDocSearchResponse(List<CanvasDocItem> items, long total) {}
