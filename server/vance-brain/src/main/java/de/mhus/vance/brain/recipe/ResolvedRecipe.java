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
        PromptMode promptMode,
        @Nullable String dataRelayCorrection,
        List<String> allowedToolsAdd,
        List<String> allowedToolsRemove,
        /**
         * Recipe-base demotion list: tools listed here are moved to the
         * deferred bucket (LLM sees them only via the discovery block,
         * activated through {@code describe_tool}). Profile and per-mode
         * overlays can promote individual entries back to primary via
         * {@code allowedToolsAdd}. See {@code planning/tool-schema-deferral.md} §4 / §14.
         */
        List<String> allowedToolsDefer,
        /**
         * Engine-mode overlay at the recipe-base level. Used when the
         * recipe doesn't have profile-specific mode blocks but still
         * wants per-mode tool restrictions. The cascade in
         * {@link RecipeResolver#toolFilterFor} consults this last,
         * after profile-specific mode blocks.
         */
        Map<String, RecipeModeBlock> modes,
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
