package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO for a single action including its Markdown body (detail load). */
@GenerateTypeScript("gtd")
public record GtdActionContentView(
        String id,
        String path,
        String title,
        String when,
        @Nullable String deadline,
        List<String> contexts,
        boolean done,
        @Nullable String project,
        String body) {}
