package de.mhus.vance.api.recipe;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One entry in the user-facing recipe-picker list. Returned by
 * {@code GET /brain/{tenant}/projects/{project}/recipes/listed}.
 *
 * <p>Backed by the recipe-YAML fields {@code title} (optional display
 * name), {@code description} (one-paragraph blurb), {@code category}
 * (optional picker-group key), and the recipe name itself. Only recipes
 * that opt in via {@code listed: true} — and are not {@code internal: true}
 * — appear in the response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("recipe")
public class RecipeListedDto {

    /** Stable recipe identifier. Sent back as {@code chatRecipe} on session bootstrap. */
    private String name;

    /** Optional human-readable label. When absent, clients should fall back to {@link #name}. */
    private @Nullable String title;

    /** Recipe description (free-form, multi-line). May be {@code null} if the YAML omitted it. */
    private @Nullable String description;

    /**
     * Optional picker-group key ({@code category:} in the recipe YAML, kebab-case).
     * Recipes sharing a key are rendered as one group in the recipe picker; the
     * group order comes from {@code _vance/config/recipe_categories.yaml} (see
     * {@link RecipeCategoryDto}). {@code null} means "no category" — those recipes
     * form the trailing group.
     */
    private @Nullable String category;
}
