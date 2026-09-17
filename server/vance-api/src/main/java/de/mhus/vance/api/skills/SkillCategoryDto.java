package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One category group of the user-facing skill picker.
 *
 * <p>Backed by {@code _vance/config/skill_categories.yaml}, cascade-resolved
 * project → {@code _vance} → bundled. The document is an ordering and
 * labelling <em>help</em>, not a registry — the same contract as
 * {@code RecipeCategoryDto} for the recipe picker (recipes.md §6e): it is
 * neither complete (categories not named there still appear, appended after
 * the documented ones) nor mandatory (no document means the response carries
 * no categories — groups still form, alphabetically, and clients fall back
 * to humanised category ids as labels).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class SkillCategoryDto {

    /**
     * Category key. Matches the {@code category:} field of the skills it
     * groups; kebab-case, normalised at parse time (trim, lower-case).
     */
    private String id;

    /**
     * Localised display labels — language code → text, e.g.
     * {@code {en: "Coding", de: "Programmierung"}}. An open locale map on
     * purpose: the server never resolves a locale, clients pick by their UI
     * language ({@code title[locale] || title.en || humanized id}). May be
     * {@code null} or missing entries for any language — consumers must
     * fall back to a humanised {@link #id}.
     */
    private @Nullable Map<String, String> title;
}
