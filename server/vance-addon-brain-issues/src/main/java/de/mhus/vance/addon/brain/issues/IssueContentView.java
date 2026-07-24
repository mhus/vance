package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO for a single issue including body + comment thread. */
@GenerateTypeScript("issues")
public record IssueContentView(
        String id,
        String path,
        int number,
        String title,
        String state,
        List<String> labels,
        @Nullable String assignee,
        @Nullable String priority,
        boolean archived,
        String body,
        List<IssueCommentView> comments) {}
