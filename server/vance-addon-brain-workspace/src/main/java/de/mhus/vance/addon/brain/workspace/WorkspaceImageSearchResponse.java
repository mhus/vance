package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Response body for {@code GET /brain/{tenant}/addon/workspace/images}.
 * Slim image-search projection used by the asset picker — the embedded
 * channel never needs the full document metadata.
 */
@GenerateTypeScript("workspace")
public record WorkspaceImageSearchResponse(
        List<WorkspaceImageItem> items,
        long total) {}
