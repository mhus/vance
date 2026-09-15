package de.mhus.vance.brain.skill;

/**
 * Thrown when an explicitly activated skill resolves but is disabled
 * ({@code enabled: false}). Disabled skills are neither implicitly nor
 * explicitly activatable — but unlike {@link UnknownSkillException} the
 * message says what the name actually is: usually a shipped template or
 * an operator's off-switch, so the answer is a copy to enable, not a
 * correction of the name.
 */
public class DisabledSkillException extends RuntimeException {

    public DisabledSkillException(String name) {
        super("Skill '" + name + "' is disabled (enabled: false). It is a template or was "
                + "switched off — copy the skill folder into the project or tenant layer "
                + "and set enabled: true there to use it.");
    }
}
