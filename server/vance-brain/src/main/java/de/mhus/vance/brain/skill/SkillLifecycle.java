package de.mhus.vance.brain.skill;

/**
 * How long a skill's activation lives. Declared in the SKILL.md
 * frontmatter ({@code lifecycle:}); default {@link #TURN} (skills.md §2a,
 * planning/skill-activation-levels.md §8). See also
 * {@code planning/engine-commands.md} §4.3.
 */
public enum SkillLifecycle {

    /**
     * Task-scoped (the default): an activation applies to exactly one turn
     * and clears itself afterwards. Activated before the turn (word trigger,
     * {@code /skill}) the body sits in that turn's system prompt; activated
     * by the agent mid-turn ({@code skill_activate}) the instructions come
     * back as the tool result and work right away. Either way nothing stays
     * in the prompt once the task is done. {@code /skill <name> --sticky}
     * keeps one anyway; recipe {@code defaultActiveSkills} are always
     * sticky.
     */
    TURN,

    /**
     * Standing mode: activation persists into the process's
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
