package de.mhus.vance.brain.wizard;

/**
 * Innermost cascade layer that produced a wizard, used as the
 * {@code source} string on
 * {@link de.mhus.vance.api.wizard.WizardSummaryDto} and
 * {@link de.mhus.vance.api.wizard.WizardDto}.
 *
 * <p>{@code USER} is wizard-specific (lookup against the
 * {@code _user_<userId>} project). Other cascade-aware subsystems
 * (recipes, documents) stop at PROJECT/VANCE/RESOURCE.
 */
public enum WizardSource {
    PROJECT,
    USER,
    VANCE,
    RESOURCE
}
