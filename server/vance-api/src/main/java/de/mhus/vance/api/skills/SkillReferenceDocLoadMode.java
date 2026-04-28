package de.mhus.vance.api.skills;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * When a skill's reference document is materialized into the prompt.
 */
@GenerateTypeScript("skills")
public enum SkillReferenceDocLoadMode {
    /** Embedded into the system prompt at skill-activation time. */
    INLINE,
    /**
     * Loaded only when the engine calls
     * {@code skill_reference_doc(skill, title)}. Not yet supported in
     * v1; treated as INLINE for now.
     */
    ON_DEMAND
}
