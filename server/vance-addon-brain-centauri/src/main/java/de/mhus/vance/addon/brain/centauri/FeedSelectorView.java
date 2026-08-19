package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** One selectable stream of a source, for the configuration form. */
@GenerateTypeScript("centauri")
public record FeedSelectorView(
        String value,
        String label,
        String kind,
        @Nullable String language) {}
