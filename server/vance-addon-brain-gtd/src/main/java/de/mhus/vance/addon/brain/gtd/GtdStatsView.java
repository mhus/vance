package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;

/** Wire DTO mirroring {@link GtdStatsBuilder.Stats}. */
@GenerateTypeScript("gtd")
public record GtdStatsView(
        int totalOpen,
        int done,
        int overdue,
        Map<String, Integer> bucketCounts,
        Map<String, Integer> contextCounts,
        Map<String, Integer> projectCounts) {}
