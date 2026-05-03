package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.thinkprocess.PromptMode;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Cascade-resolved view of one recipe — what the spawner uses after
 * {@link RecipeResolver#resolve} but <em>before</em> caller-supplied
 * params have been merged in. Carries the parsed YAML fields plus the
 * cascade source attribution.
 */
public record ResolvedRecipe(
        String name,
        String description,
        String engine,
        Map<String, Object> params,
        @Nullable String promptPrefix,
        @Nullable String promptPrefixSmall,
        PromptMode promptMode,
        @Nullable String dataRelayCorrection,
        List<String> allowedToolsAdd,
        List<String> allowedToolsRemove,
        Map<String, ProfileBlock> profiles,
        /**
         * Skill names that are sticky-activated on the spawned process
         * with {@code fromRecipe=true}. Empty list means "no skills
         * pinned by this recipe".
         */
        List<String> defaultActiveSkills,
        /**
         * Whitelist of skill names that may ever be active on the spawned
         * process — covers trigger-matched, default-active, and {@code /skill}
         * activations. {@code null} means "no restriction" (heutiges
         * Default-Verhalten); an empty list means complete lock-down.
         */
        @Nullable List<String> allowedSkills,
        boolean locked,
        List<String> tags,
        RecipeSource source) {
}
