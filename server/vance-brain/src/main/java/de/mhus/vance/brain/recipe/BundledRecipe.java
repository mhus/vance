package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.thinkprocess.PromptMode;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Immutable in-memory snapshot of one entry in
 * {@code recipes.yaml}. Fields mirror {@code RecipeDocument} so a
 * bundled recipe and a Mongo-stored recipe can be treated
 * interchangeably by the resolver.
 */
public record BundledRecipe(
        String name,
        String description,
        String engine,
        Map<String, Object> params,
        @Nullable String promptPrefix,
        PromptMode promptMode,
        List<String> allowedToolsAdd,
        List<String> allowedToolsRemove,
        boolean locked,
        List<String> tags) {
}
