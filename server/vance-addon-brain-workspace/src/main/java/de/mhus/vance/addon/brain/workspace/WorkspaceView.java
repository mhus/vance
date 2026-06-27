package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO returned by the workspace REST scan endpoint. */
@GenerateTypeScript("workspace")
public record WorkspaceView(
        String folder,
        @Nullable String title,
        @Nullable String description,
        @Nullable String landingPagePath,
        @Nullable String landingPageId,
        @Nullable String indexPagePath,
        @Nullable String indexPageId,
        List<WorkspacePageView> pages) {}
