package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one search hit. */
@GenerateTypeScript("gtd")
public record GtdHitView(
        String id,
        String path,
        @Nullable String title,
        String snippet,
        int score) {}
