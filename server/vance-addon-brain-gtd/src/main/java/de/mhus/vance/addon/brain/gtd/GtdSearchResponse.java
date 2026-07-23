package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** Wire DTO returned by the GTD search endpoint. */
@GenerateTypeScript("gtd")
public record GtdSearchResponse(
        List<GtdHitView> items,
        long total) {}
