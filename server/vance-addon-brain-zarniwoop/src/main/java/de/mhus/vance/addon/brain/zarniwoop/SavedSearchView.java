package de.mhus.vance.addon.brain.zarniwoop;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** One search worth keeping, as stored in the manifest. */
@GenerateTypeScript("search")
public record SavedSearchView(
        String name,
        String query,
        String modality,
        String tier,
        @Nullable String instance) {}
