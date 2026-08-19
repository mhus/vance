package de.mhus.vance.addon.brain.zarniwoop;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The manifest of one search surface.
 *
 * <p>Short, because a search has no state worth keeping beyond the shape of the
 * surface. What is deliberately absent is a history — a search log written
 * without being asked for is a usage trace.
 */
@GenerateTypeScript("search")
public record SearchConfigView(
        String folder,
        @Nullable String title,
        String defaultModality,
        int defaultNum,
        List<SavedSearchView> savedSearches) {}
