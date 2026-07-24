package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Request body for {@code PATCH /issue}. Every field optional. */
@GenerateTypeScript("issues")
public record IssuePatchRequest(
        @Nullable String state,
        @Nullable List<String> labels,
        @Nullable String assignee,
        @Nullable String priority,
        @Nullable String title,
        @Nullable String body) {}
