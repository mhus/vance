package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one issue in a list (no body/comments). */
@GenerateTypeScript("issues")
public record IssueView(
        String id,
        String path,
        int number,
        String title,
        String state,
        List<String> labels,
        @Nullable String assignee,
        @Nullable String priority,
        boolean archived) {}
