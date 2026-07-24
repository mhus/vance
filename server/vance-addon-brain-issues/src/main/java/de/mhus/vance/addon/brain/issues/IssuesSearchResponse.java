package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** Wire DTO returned by the issues search endpoint. */
@GenerateTypeScript("issues")
public record IssuesSearchResponse(
        List<IssueHitView> items,
        long total) {}
