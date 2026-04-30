package de.mhus.vance.brain.skill;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when a caller tries to activate a skill that is not in the
 * spawning recipe's {@code allowedSkills} whitelist. Persisted at spawn
 * time on {@code ThinkProcessDocument.allowedSkillsOverride} as a
 * snapshot, so later recipe edits do not retro-affect the running
 * process.
 */
public class SkillNotAllowedByRecipeException extends RuntimeException {

    public SkillNotAllowedByRecipeException(String name, @Nullable String recipeName) {
        super("Skill '" + name + "' is not allowed by recipe '"
                + (recipeName == null ? "?" : recipeName) + "'");
    }
}
