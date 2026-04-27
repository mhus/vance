/**
 * Brain-side recipe layer — bundled YAML registry, cascade resolver,
 * and the discovery tools the LLM uses to pick recipes. Recipe
 * persistence (tenant/project Mongo overrides) lives in
 * {@code de.mhus.vance.shared.recipe}.
 */
@NullMarked
package de.mhus.vance.brain.recipe;

import org.jspecify.annotations.NullMarked;
