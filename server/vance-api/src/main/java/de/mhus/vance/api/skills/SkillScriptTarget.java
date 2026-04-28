package de.mhus.vance.api.skills;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Where a skill-attached JavaScript snippet runs.
 *
 * <p>The persistence model in v1 just records the target — the
 * runtime mounting that turns scripts into callable tools is a later
 * phase (see {@code specification/skills.md} §13).
 */
@GenerateTypeScript("skills")
public enum SkillScriptTarget {
    /**
     * Server-side execution in the brain's
     * {@code de.mhus.vance.brain.tools.js.JsEngine} (GraalJS sandbox).
     * Future host-bindings will give it access to the project workspace
     * and other brain tools.
     */
    BRAIN,
    /**
     * Client-side execution in the foot CLI's
     * {@code de.mhus.vance.foot.tools.js.ClientJsEngine}. The brain
     * routes the script + args through the WebSocket connection and
     * waits for the result.
     */
    FOOT
}
