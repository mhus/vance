package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** One selectable value; {@code parentId} null is a root. */
@GenerateTypeScript("centauri")
public record FeedFacetValueView(String id, String label, @Nullable String parentId) {}
