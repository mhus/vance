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
 * name), {@code description} (one-paragraph blurb), and the recipe
 * name itself. Only recipes that opt in via {@code listed: true} —
 * and are not {@code internal: true} — appear in the response.
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
}
