package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one search hit. */
@GenerateTypeScript("issues")
public record IssueHitView(
        String id,
        String path,
        @Nullable String title,
        String snippet,
        int score) {}
