package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response to a {@link ProcessSkillRequest}. Always carries the
 * post-mutation snapshot of {@link #activeSkills} so the client UI can
 * refresh its badge without a follow-up roundtrip.
 *
 * <p>{@link #availableSkills} is populated only for
 * {@link ProcessSkillCommand#LIST} — the union of skills visible in
 * the current process's scope (cascade-deduped, see
 * {@code SkillResolver.listAvailable}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class ProcessSkillResponse {

    private String processName;

    @Builder.Default
    private List<ActiveSkillRefDto> activeSkills = new ArrayList<>();

    /** Populated for {@link ProcessSkillCommand#LIST} responses. */
    @Builder.Default
    private List<SkillSummaryDto> availableSkills = new ArrayList<>();

    /**
     * Category metadata from {@code _vance/config/skill_categories.yaml},
     * populated for {@link ProcessSkillCommand#LIST} responses. Order and
     * labels for grouped rendering of {@link #availableSkills} — the same
     * sort-help contract as the recipe picker's category document
     * (recipes.md §6e): absent when no tier carries the document, in
     * which case clients render unlabelled groups from the skills' own
     * {@code category} values. Other commands omit it.
     */
    private List<SkillCategoryDto> categories;
    /**
     * Populated for {@link ProcessSkillCommand#ACTIVATE} responses:
     * {@code true} when this call activated the skill freshly (firing its
     * {@code action:} turn), {@code false} when it was already active
     * (arguments updated at most). The UI needs the difference — "activated"
     * and "already active" are different sentences, and a fired turn is
     * visible in the chat as work starting.
     */
    private Boolean newlyActivated;

    /** Lifecycle of the activated skill, for {@link ProcessSkillCommand#ACTIVATE} replies. */
    private String lifecycle;
}
