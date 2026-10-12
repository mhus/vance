package de.mhus.vance.api.skills;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Target override scope of a {@link SkillModeRequest} — which
 * {@code _vance/config/skill_activation.yaml} tier the mode lands in.
 * The write permission for the non-user tiers is enforced at the
 * document source, like every document write.
 */
@GenerateTypeScript("skills")
public enum SkillModeScope {
    /** The caller's own {@code _user_<login>} hub project. Innermost, always writable. */
    USER,
    /** The caller's current project — every project member with write access. */
    PROJECT,
    /** The tenant-wide {@code _vance} system project. */
    TENANT
}
