package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Cascade-resolved view of one recipe for the project-insights tab.
 * Mirrors {@code de.mhus.vance.brain.recipe.ResolvedRecipe} but
 * trimmed to wire-friendly fields and without the per-engine
 * profile-block detail (callers that want that depth use the recipe
 * editor instead).
 *
 * <p>{@link #source} is the innermost cascade layer that produced
 * this recipe — one of {@code PROJECT}, {@code VANCE} (the tenant's
 * {@code _vance} system project), or {@code RESOURCE} (the bundled
 * classpath default). Recipes show up exactly once even when shadowed
 * by inner layers; the API returns only the winning entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class EffectiveRecipeDto {

    private String name;

    private String description;

    private String engine;

    /** {@code PROJECT} | {@code VANCE} | {@code RESOURCE}. */
    private String source;

    /** Number of params declared at recipe level (before profile-block merge). */
    private int paramsCount;

    /** Whether the recipe carries a non-empty {@code promptPrefix}. */
    private boolean hasPromptPrefix;

    /** Whether the recipe carries a non-empty {@code promptPrefixSmall}. */
    private boolean hasPromptPrefixSmall;

    private List<String> allowedToolsAdd;

    private List<String> allowedToolsRemove;

    private List<String> defaultActiveSkills;

    /** {@code null} = no restriction; empty list = lock-down. */
    private @Nullable List<String> allowedSkills;

    private boolean locked;

    private List<String> tags;

    /** Profile-block keys present (e.g. {@code mobile}, {@code web}). */
    private List<String> profileKeys;
}
