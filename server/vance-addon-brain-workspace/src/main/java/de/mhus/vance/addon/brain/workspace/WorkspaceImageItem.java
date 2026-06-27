package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Slim image projection — one entry of a workspace image search. */
@GenerateTypeScript("workspace")
public record WorkspaceImageItem(
        String id,
        String path,
        String name,
        @Nullable String mimeType) {}
