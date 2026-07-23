package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one action in a bucket / list (no body). */
@GenerateTypeScript("gtd")
public record GtdActionView(
        String id,
        String path,
        String title,
        String when,
        @Nullable String deadline,
        List<String> contexts,
        boolean done,
        String bucket,
        @Nullable String project,
        boolean overdue) {}
