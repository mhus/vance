package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO returned by the issues rebuild endpoint. */
@GenerateTypeScript("issues")
public record IssuesRebuildResponse(
        String folder,
        @Nullable String indexPath,
        @Nullable String statsPath,
        int open,
        int closed) {}
