package de.mhus.vance.api.skills;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Cascade layer that produced a skill lookup result. Mirrors the
 * document cascade plus a USER tier (from the per-user
 * {@code _user_<login>} system project), since skills are persisted
 * as documents under {@code skills/<name>/SKILL.md}.
 *
 * <p>Cascade priority: {@link #USER} → {@link #PROJECT} → {@link #VANCE}
 * → {@link #RESOURCE}; first-hit-wins.
 */
@GenerateTypeScript("skills")
public enum SkillScope {
    /** From the per-user {@code _user_<login>} system project. Innermost. */
    USER,
    /** From the user's project. */
    PROJECT,
    /** From the tenant-wide {@code _vance} system project. */
    VANCE,
    /** From a bundled classpath resource under {@code vance-defaults/skills/}. */
    RESOURCE
}
