package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Request body for {@code POST /issue}. */
@GenerateTypeScript("issues")
public record IssueCreateRequest(
        String title,
        @Nullable List<String> labels,
        @Nullable String assignee,
        @Nullable String priority,
        @Nullable String body) {}
