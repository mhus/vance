package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Write request for a skill's activation mode — {@code POST
 * /brain/{tenant}/skills/{skillName}/activation}. Scope configuration,
 * not process state: unlike {@code process-skill} there is no process
 * involved, which is exactly why this is a separate surface
 * (planning/skill-activation-levels.md §3.5).
 *
 * <p>The mode lands in the scope's override document
 * ({@code _vance/config/skill_activation.yaml}, cascade user → project
 * → tenant) — never in the skill's own SKILL.md, so the override survives
 * updates of the original skill.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class SkillModeRequest {

    /**
     * Target override scope. {@code user} (default) writes the caller's
     * own {@code _user_<login>} layer — always allowed; {@code project}
     * and {@code tenant} write the caller's project / the tenant-wide
     * layer and require write access there (enforced at the document
     * source, like every document write).
     */
    @Builder.Default
    private SkillModeScope scope = SkillModeScope.USER;

    /** The new mode: {@code manual}, {@code trigger} or {@code agent}. */
    private SkillActivation mode;
}
