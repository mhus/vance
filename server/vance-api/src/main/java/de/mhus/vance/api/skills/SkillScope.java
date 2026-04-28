package de.mhus.vance.api.skills;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Where the effective copy of a skill lives.
 *
 * <p>Cascade order on lookup is USER → PROJECT → TENANT → BUNDLED;
 * first-hit-wins. Listing operations return the union with
 * deduplication by skill name (more specific scope wins).
 */
@GenerateTypeScript("skills")
public enum SkillScope {
    /** From a bundled {@code SKILL.md} on the brain's classpath. */
    BUNDLED,
    /** A tenant-scope override stored in MongoDB. */
    TENANT,
    /** A project-scope override stored in MongoDB. */
    PROJECT,
    /** A user-private skill stored in MongoDB. Visible only to its owner. */
    USER
}
