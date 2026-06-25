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
        /**
         * Trigger keywords parsed from the YAML {@code triggers.keywords}
         * block. Used by {@link de.mhus.vance.brain.delegate.RecipeSelectorService}
         * for the deterministic pre-check that fires before any LLM call.
         * Empty list means "no triggers" — this recipe is structurally
         * invoked (eddie, arthur, ford, default) or only by explicit name.
         * Entries are normalised to lower-case at parse time so matching
         * is case-insensitive without per-call work.
         */
        List<String> triggerKeywords,
        boolean locked,
        /**
         * Marks the recipe as an internal config profile, e.g. for
         * {@code LightLlmService} consumers (discovery, title-gen, …).
         * The {@code RecipeSelectorService} skips internal recipes when
         * listing candidates for the LLM-driven {@code DELEGATE}
         * selector — they can only be loaded by explicit name via the
         * service that owns them. Default {@code false}.
         */
        boolean internal,
        /**
         * Opt-in flag: when {@code true}, the recipe is exposed by the
         * tenant-facing "listed recipes" endpoint that drives the Web-UI
         * recipe picker (and any future client recipe pickers). Defaults
         * to {@code false} so that helper/config recipes
         * ({@code internal: true} or otherwise infrastructure-only)
         * stay out of the user-facing list unless their author explicitly
         * opts in. See {@code specification/recipes.md}.
         */
        boolean listed,
        /**
         * Optional human-readable display name for clients that surface
         * the recipe to the user (Web-UI recipe picker, future mobile
         * UIs). When {@code null}, the {@link #name} is used as fallback.
         */
        @Nullable String title,
        List<String> tags,
        /**
         * Optional post-completion hook config. When set on a Lunkwill
         * recipe, the engine spawns the configured follow-up process
         * after the worker reaches the configured stop signal — see
         * {@code planning/lunkwill-post-completion-hook.md}. Ignored
         * by non-Lunkwill engines.
         */
        @Nullable PostCompletionHookConfig postCompletionHook,
        RecipeSource source) {
}
