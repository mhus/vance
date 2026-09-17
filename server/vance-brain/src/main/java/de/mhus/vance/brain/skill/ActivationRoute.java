package de.mhus.vance.brain.skill;

/**
 * Who is asking for an activation — because "activate a skill" is not one
 * behavior. Two axes differ per caller, and they are <em>not</em> derivable
 * from each other:
 *
 * <ol>
 *   <li><b>Does a fresh activation fire the skill's {@code action:} turn?</b>
 *       The user surface wants it ({@code /skill code-review} starts the
 *       review), and so does the worker-side activation of a spawn skill
 *       (its {@code action:} is the child's first prompt — mandatory per
 *       specification/public/skills.md §2c). The agent does not: it calls
 *       {@code skill_activate} mid-turn, so a scheduled turn would duplicate
 *       the work in flight — it kicks off explicitly via {@code skill_fire}
 *       when it wants to. The implicit routes (auto-trigger, guard scripts)
 *       never fire — their enclosing turn already covers the work.</li>
 *   <li><b>May the activation start a worker?</b> Only deliberate,
 *       interactive gestures spawn ({@code run.target: spawn} skills).
 *       A keyword match or a guard script starting a worker would be
 *       expensive and surprising — there the spawn is a quiet no-op.</li>
 * </ol>
 *
 * <p>The worker-side route additionally registers a spawn skill inline when
 * spawning is not allowed: it <em>is</em> the worker, the spawn branch must
 * not re-enter (no grandchild), so the skill lands in its
 * {@code activeSkills} like any sticky skill. The implicit routes instead
 * leave the process untouched for spawn skills.
 */
public enum ActivationRoute {

    /** User surface: {@code /skill} and the {@code process-skill} WS command. */
    USER(true, true, false),

    /** Agent tool {@code skill_activate}: no action fire, spawning allowed. */
    AGENT(false, true, false),

    /**
     * Implicit activations — the auto-trigger ({@link SkillTriggerMatcher})
     * and guard scripts ({@code vance.guard.activateSkill}). No action
     * fire, no spawn; a spawn skill is an honest no-op.
     */
    IMPLICIT(false, false, false),

    /**
     * The child-side activation a spawn schedules on the fresh worker
     * (recursion guard, specification/public/skills.md §2c): fires the
     * action turn, never spawns again, registers inline.
     */
    WORKER(true, false, true);

    private final boolean fireActionTurn;
    private final boolean allowSpawn;
    private final boolean registerInlineWhenSpawnBlocked;

    ActivationRoute(boolean fireActionTurn, boolean allowSpawn, boolean registerInlineWhenSpawnBlocked) {
        this.fireActionTurn = fireActionTurn;
        this.allowSpawn = allowSpawn;
        this.registerInlineWhenSpawnBlocked = registerInlineWhenSpawnBlocked;
    }

    /** Whether a fresh activation fires the skill's {@code action:} turn. */
    public boolean fireActionTurn() {
        return fireActionTurn;
    }

    /** Whether this route may start a worker for a {@code run.target: spawn} skill. */
    public boolean allowSpawn() {
        return allowSpawn;
    }

    /**
     * Whether a spawn skill that may not spawn here falls through to inline
     * registration instead of a no-op. True only for {@link #WORKER}.
     */
    public boolean registerInlineWhenSpawnBlocked() {
        return registerInlineWhenSpawnBlocked;
    }
}
