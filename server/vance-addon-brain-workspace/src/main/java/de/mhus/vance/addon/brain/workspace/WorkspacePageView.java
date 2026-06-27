package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one page inside a workspace. */
@GenerateTypeScript("workspace")
public record WorkspacePageView(
        String id,
        String path,
        String relativePath,
        String section,
        String title,
        @Nullable String description,
        @Nullable String icon,
        @Nullable Double sortIndex) {}
