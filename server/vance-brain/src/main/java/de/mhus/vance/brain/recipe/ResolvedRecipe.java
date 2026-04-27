package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.thinkprocess.PromptMode;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Cascade-resolved view of one recipe — what the spawner uses after
 * {@link RecipeResolver#resolve} but <em>before</em> caller-supplied
 * params have been merged in. Same shape as {@link BundledRecipe}
 * plus the source attribution.
 */
public record ResolvedRecipe(
        String name,
        String description,
        String engine,
        Map<String, Object> params,
        @Nullable String promptPrefix,
        PromptMode promptMode,
        List<String> allowedToolsAdd,
        List<String> allowedToolsRemove,
        boolean locked,
        List<String> tags,
        RecipeSource source) {
}
