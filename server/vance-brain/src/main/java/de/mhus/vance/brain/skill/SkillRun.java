package de.mhus.vance.brain.skill;

import org.jspecify.annotations.Nullable;

/**
 * Where a skill's activation takes effect — the {@code run:} block of the
 * SKILL.md frontmatter. This is an axis of its own, orthogonal to
 * {@link SkillLifecycle} (how long an activation lives) and to the
 * per-invocation {@code --once} flag: it decides the <em>place</em>, not
 * the duration.
 *
 * <p>{@link Target#SPAWN} runs the skill in a freshly spawned worker that
 * inherits no chat history, which is what review-style work wants — judge
 * the code, not the discussion that produced it. The skill then registers
 * nowhere in the calling process and sticky in the child, so its body
 * becomes the worker's system prompt and its {@code action:} the kick-off.
 *
 * <p>See {@code specification/public/skills.md} §2c and
 * {@code planning/skill-spawn-target.md}.
 */
public record SkillRun(
        Target target,
        /** Recipe the child runs. Required for {@link Target#SPAWN}, else {@code null}. */
        @Nullable String recipe,
        /**
         * Inherit level handed to the spawn as
         * {@code TriggerAction.Recipe.inheritContextLevel}. Defaults to
         * {@link #DEFAULT_INHERIT} for a spawn — the point of spawning is
         * to start without the parent's history.
         */
        @Nullable String inherit) {

    /** Default: the skill acts in the calling process. */
    public static final SkillRun INLINE = new SkillRun(Target.INLINE, null, null);

    /** Inherit level a spawn falls back to when the skill names none. */
    public static final String DEFAULT_INHERIT = "none";

    public enum Target {

        /** Body, commands and turn-prompt all act on the calling process. */
        INLINE,

        /** A fresh child process carries the skill; the caller keeps nothing. */
        SPAWN
    }

    public boolean spawns() {
        return target == Target.SPAWN;
    }
}
