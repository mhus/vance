package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Response body for {@code GET /brain/{tenant}/addon/workspace/documents/search}.
 * Used by the link picker to find any document in the current project
 * by name / path substring (recursive).
 */
@GenerateTypeScript("workspace")
public record WorkspaceDocumentSearchResponse(
        List<WorkspaceDocumentItem> items,
        long total) {}
