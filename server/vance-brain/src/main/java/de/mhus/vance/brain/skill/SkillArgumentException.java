package de.mhus.vance.brain.skill;

/**
 * Thrown when a skill invocation's trailing text cannot be bound to the
 * skill's declared {@code arguments:} — a missing required argument or a
 * token that does not parse as the declared type. Raised by
 * {@link SkillArgumentBinder} <b>before</b> the activation takes effect,
 * so the caller gets a usable error instead of a prompt rendered with
 * silently empty placeholders.
 */
public class SkillArgumentException extends IllegalArgumentException {

    public SkillArgumentException(String skillName, String argumentName) {
        super("Skill '" + skillName + "' requires argument '" + argumentName + "'");
    }

    public SkillArgumentException(
            String skillName, String argumentName, String type, String value) {
        super("Skill '" + skillName + "' argument '" + argumentName
                + "' expects type " + type + " — got '" + value + "'");
    }
}
