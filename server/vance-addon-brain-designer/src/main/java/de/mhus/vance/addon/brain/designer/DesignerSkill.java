package de.mhus.vance.addon.brain.designer;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One design skill in the designer app's skill catalogue — a skill tagged
 * {@code design} (the label convention from the app manual). Carried by
 * {@link DesignerSkillList}.
 *
 * <p>{@code active} is only set when the caller asked with a chat context
 * (session + process name): skills activate per think-process, so without
 * an open chat there is no activation state to report and the field stays
 * absent — the UI shows its no-chat hint instead of a wrong "inactive".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("designer")
public class DesignerSkill {

    /** Skill name — the {@code /skill <name>} handle. */
    private String name;

    /** Display name from the SKILL.md frontmatter. */
    private String title;

    /** One-line description from the frontmatter. */
    private @Nullable String description;

    /** Frontmatter version, for the detail line. */
    private @Nullable String version;

    /** Which cascade tier answered: {@code user}, {@code project}, {@code vance} or {@code resource}. */
    private String source;

    /**
     * Whether the skill carries a {@code style.css} sibling — the design
     * convention. Only then does the catalogue offer a live style preview;
     * without it the row shows a plain card.
     */
    private boolean style;

    /**
     * Whether the skill is active in the addressed chat process. Absent
     * when the listing was asked without a chat context.
     */
    /**
     * Whether the skill is active in the addressed chat process. Absent
     * when the listing was asked without a chat context.
     */
    private @Nullable Boolean active;

    /**
     * For an active skill: whether the recipe bound it (not the user) —
     * those cannot be cleared, the UI disables its ✕. Absent when
     * inactive or asked without a chat context.
     */
    private @Nullable Boolean fromRecipe;
}
