/**
 * Live field suggestions: the {@link de.mhus.vance.brain.suggestion.SuggestionSource}
 * SPI behind {@code FormFieldDto.suggestionsFrom}. The core owns the route,
 * addons own the sources — the core never learns what a source searches.
 */
@NullMarked
package de.mhus.vance.brain.suggestion;

import org.jspecify.annotations.NullMarked;
