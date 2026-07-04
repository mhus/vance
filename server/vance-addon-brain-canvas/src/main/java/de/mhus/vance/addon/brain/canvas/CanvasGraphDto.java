package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO for a full canvas page — display metadata + the node/edge graph. */
@GenerateTypeScript("canvas")
public record CanvasGraphDto(
        @Nullable String title,
        @Nullable String description,
        List<CanvasNodeDto> nodes,
        List<CanvasEdgeDto> edges) {}
