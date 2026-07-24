package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO returned by the issues REST scan endpoint. */
@GenerateTypeScript("issues")
public record IssuesView(
        String folder,
        @Nullable String title,
        @Nullable String description,
        List<String> labels,
        List<IssueView> issues,
        IssueStatsView stats,
        boolean archivedView) {}
