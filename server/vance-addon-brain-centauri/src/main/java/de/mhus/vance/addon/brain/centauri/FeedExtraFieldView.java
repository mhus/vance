package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * One {@code extras} key the source says is worth showing, and its label.
 *
 * <p>The card renders exactly these, in this order. The list is the source's
 * because the keys are: a reader that hardcoded them would show nothing for
 * the next source and miss whatever that one carries instead.
 */
@GenerateTypeScript("centauri")
public record FeedExtraFieldView(String key, String label) {}
