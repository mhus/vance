package de.mhus.vance.brain.skill;

/**
 * Thrown when a skill name cannot be resolved through the cascade and
 * the caller asked for a hard match (e.g. user-explicit
 * {@code /skill <name>} on a non-existent skill).
 */
public class UnknownSkillException extends RuntimeException {

    public UnknownSkillException(String name) {
        super("Unknown skill '" + name + "'");
    }
}
