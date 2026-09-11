package de.mhus.vance.api.recipe;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response of {@code GET /brain/{tenant}/projects/{project}/recipes/listed}.
 *
 * <p>Carries the recipe entries (sorted: category-group order first, then
 * display title case-insensitive; entries without a category last) plus the
 * category metadata in document order, so clients can render group headers
 * with localised labels without a second request. {@code categories} is empty
 * when the category document is absent or malformed — fail-open, the picker
 * must not stop working because of a broken config document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("recipe")
public class RecipeListedResponse {

    /**
     * Category metadata from {@code _vance/config/recipe_categories.yaml},
     * in document order. Only categories that at least one listed recipe
     * carries are guaranteed to be useful, but the document order is sent
     * as-is — a client that wants to show an empty group may.
     */
    private List<RecipeCategoryDto> categories;

    /** The listed recipes ({@code listed: true}, not {@code internal: true}). */
    private List<RecipeListedDto> recipes;
}
