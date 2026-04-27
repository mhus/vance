package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.thinkprocess.PromptMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Final recipe configuration after the caller's overrides have been
 * merged in. This is the snapshot the spawner uses to populate the
 * fresh {@code ThinkProcessDocument}.
 *
 * <p>{@link #effectiveAllowedTools} is computed from the engine's
 * declared {@code allowedTools()} plus the recipe's
 * {@code allowedToolsAdd} minus {@code allowedToolsRemove}; if it
 * matches the engine default exactly, the resolver returns
 * {@code null} to signal "no override needed" — callers then leave
 * the process's {@code allowedToolsOverride} field empty.
 */
public record AppliedRecipe(
        String name,
        String engine,
        Map<String, Object> params,
        @Nullable String promptOverride,
        PromptMode promptMode,
        @Nullable Set<String> effectiveAllowedTools,
        RecipeSource source,
        List<String> overriddenParamKeys) {
}
