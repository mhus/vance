package de.mhus.vance.addon.brain.designer;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The design-skill catalogue — what
 * {@code GET /addon/designer/design-skills} returns: every skill tagged
 * {@code design} that is visible in the app's scope (cascade-deduped by
 * name, disabled skills skipped by the resolver's listing), sorted by
 * title, with the chat activation state joined in when the caller
 * passed a session and process name.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("designer")
public class DesignerSkillList {

    /** The design skills, sorted by title. */
    @Builder.Default
    private List<DesignerSkill> skills = new ArrayList<>();
}
