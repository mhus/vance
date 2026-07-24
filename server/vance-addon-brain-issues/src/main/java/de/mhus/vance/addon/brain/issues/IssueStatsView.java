package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;

/** Wire DTO mirroring {@link IssuesStatsBuilder.Stats}. */
@GenerateTypeScript("issues")
public record IssueStatsView(
        int open,
        int closed,
        int total,
        Map<String, Integer> byLabel,
        Map<String, Integer> byAssignee) {}
