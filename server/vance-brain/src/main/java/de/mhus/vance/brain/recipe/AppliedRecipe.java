package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.session.SessionLifecycleConfig;
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
        @Nullable String promptOverrideSmall,
        PromptMode promptMode,
        @Nullable String intentCorrection,
        @Nullable String dataRelayCorrection,
        @Nullable Set<String> effectiveAllowedTools,
        /**
         * Connection-profile that was active when this recipe was applied.
         * Persisted on the spawned process for audit / late introspection;
         * not used for engine logic.
         */
        @Nullable String connectionProfile,
        /**
         * Skill names to seed the spawned process's {@code activeSkills}
         * list with. Each entry becomes an {@code ActiveSkillRefEmbedded}
         * with {@code fromRecipe=true}, sticky.
         */
        List<String> defaultActiveSkills,
        /**
         * Whitelist passed through to {@code allowedSkillsOverride} on
         * the spawned process. {@code null} means "no restriction".
         */
        @Nullable List<String> allowedSkills,
        RecipeSource source,
        List<String> overriddenParamKeys,
        /**
         * Session-lifecycle config from {@code profiles.{profile}.session}.
         * Only meaningful for the bootstrap recipe of a session — worker
         * recipes ignore this. {@code null} when the resolved profile-block
         * had no {@code session:} sub-block.
         */
        @Nullable SessionLifecycleConfig sessionLifecycleConfig) {
}
