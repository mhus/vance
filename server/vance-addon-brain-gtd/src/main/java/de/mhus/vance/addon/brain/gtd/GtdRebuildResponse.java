package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO returned by the GTD rebuild endpoint. */
@GenerateTypeScript("gtd")
public record GtdRebuildResponse(
        String folder,
        @Nullable String todayPath,
        @Nullable String upcomingPath,
        @Nullable String statsPath,
        int totalOpen,
        int inbox) {}
