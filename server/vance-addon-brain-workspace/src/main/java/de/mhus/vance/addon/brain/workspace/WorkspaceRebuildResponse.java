package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO returned by the workspace rebuild endpoint. */
@GenerateTypeScript("workspace")
public record WorkspaceRebuildResponse(
        String folder,
        String indexPath,
        @Nullable String indexLink,
        int pageCount) {}
