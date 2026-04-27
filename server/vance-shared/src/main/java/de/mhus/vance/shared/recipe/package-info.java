/**
 * Persistent layer for {@code Recipe}s — named, versioned bundles of
 * engine + default-params + prompt-prefix + tool-adjustments. Recipes
 * are the user-facing abstraction over engines: orchestrators (Arthur)
 * spawn workers via recipe name, the resolver fills in the
 * configuration. See {@code specification/recipes.md}.
 */
@NullMarked
package de.mhus.vance.shared.recipe;

import org.jspecify.annotations.NullMarked;
