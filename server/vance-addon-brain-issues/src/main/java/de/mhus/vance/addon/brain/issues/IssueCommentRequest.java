package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/** Request body for {@code POST /issue/comment}. */
@GenerateTypeScript("issues")
public record IssueCommentRequest(
        String text) {}
