package de.mhus.vance.brain.settingform;

/**
 * Innermost cascade layer that produced a Setting Form. Mirrors
 * {@link de.mhus.vance.brain.wizard.WizardSource} so the wire-format
 * source strings are interchangeable across the two subsystems.
 */
public enum SettingFormSource {
    PROJECT,
    USER,
    VANCE,
    RESOURCE
}
