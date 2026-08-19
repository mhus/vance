package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** A stream reference: one source, one selector. */
@GenerateTypeScript("centauri")
public record FeedStreamView(
        String source,
        @Nullable String selector) {}
