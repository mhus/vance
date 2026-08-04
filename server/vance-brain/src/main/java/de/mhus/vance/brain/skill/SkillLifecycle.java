package de.mhus.vance.brain.skill;

/**
 * How a skill's activation behaves. Declared in the SKILL.md
 * frontmatter ({@code lifecycle:}); default {@link #STICKY}. See
 * {@code planning/engine-commands.md} §4.3.
 */
public enum SkillLifecycle {

    /**
     * Normal skill: activation persists into the process's
     * {@code activeSkills}, the prompt body is injected on every turn
     * while active, and {@code deactivate:} fires on clear.
     */
    STICKY,

    /**
     * Pure-configuration macro: activation fires the {@code activate:}
     * command sequence <b>once</b> and is never added to
     * {@code activeSkills} — no body injection, no {@code deactivate:}.
     * The configuration applied by the commands is meant to outlive the
     * skill; the skill is just the vehicle.
     */
    SHOT
}
