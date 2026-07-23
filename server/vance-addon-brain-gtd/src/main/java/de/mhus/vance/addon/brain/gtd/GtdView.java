package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO returned by the GTD REST scan endpoint — all buckets, projects, contexts, stats. */
@GenerateTypeScript("gtd")
public record GtdView(
        String folder,
        @Nullable String title,
        @Nullable String description,
        List<String> contexts,
        List<GtdBucketView> buckets,
        List<GtdProjectView> projects,
        GtdStatsView stats) {}
