package de.mhus.vance.api.skills;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Provenance of a skill's effective {@link SkillActivation} mode —
 * shown in the skill panel so a user can tell an author's decision
 * (frontmatter) from their own override (config document) and from
 * the {@code triggers:}-based default (planning/
 * skill-activation-levels.md §3.5).
 */
@GenerateTypeScript("skills")
public enum SkillActivationSource {
    /** Declared {@code activation:} value in the SKILL.md frontmatter. */
    FRONTMATTER,
    /** No declaration — derived from {@code triggers:} presence. */
    DERIVED,
    /** Scope-level override document; the tier is named by the scope field. */
    OVERRIDE
}
