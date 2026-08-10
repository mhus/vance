package de.mhus.vance.brain.skill;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when a {@code run.target: spawn} skill could not get its worker
 * off the ground — unknown recipe, engine start failure, or no free
 * process name after {@link SkillSpawnRunner#MAX_NAME_ATTEMPTS} tries.
 *
 * <p>Deliberately loud: the caller asked for work to happen elsewhere,
 * and unlike an inline activation there is no half-result to fall back
 * on. Swallowing this would leave the user waiting for a worker that
 * never existed.
 */
public class SkillSpawnException extends RuntimeException {

    public SkillSpawnException(String skillName, String reason) {
        super("Skill '" + skillName + "' could not spawn its worker: " + reason);
    }

    public SkillSpawnException(String skillName, String reason, @Nullable Throwable cause) {
        super("Skill '" + skillName + "' could not spawn its worker: " + reason, cause);
    }
}
