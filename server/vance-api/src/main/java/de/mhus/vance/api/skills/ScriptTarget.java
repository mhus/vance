package de.mhus.vance.api.skills;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Where a skill-script executes. Mirrors the two
 * {@code ScriptExecutor} implementations defined in
 * {@code specification/script-engine.md} §1.1.
 */
@GenerateTypeScript("skills")
public enum ScriptTarget {
    /** Server-side run inside the brain's {@code ScriptExecutor}.
     *  Has access to the full {@code vance.*} host-API surface. */
    BRAIN,
    /** Client-side run inside the foot's {@code ClientScriptExecutor}.
     *  Routed through the WS-protocol — Phase-4 of
     *  {@code specification/skills.md} §13. Not implemented in v1;
     *  declarations are accepted but the loader warns and drops
     *  the entry. */
    FOOT
}
