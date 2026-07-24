package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one comment (backed by a DocumentNote). */
@GenerateTypeScript("issues")
public record IssueCommentView(
        String id,
        String text,
        String userId,
        @Nullable String createdAt,
        @Nullable String updatedAt) {}
