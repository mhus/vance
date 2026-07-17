package de.mhus.vance.brain.template;

/**
 * Cascade layer a template was resolved from. Three tiers — templates
 * (unlike wizards) have no per-user layer in v1.
 */
public enum TemplateSource {
    /** The user's project. Innermost; overrides {@link #VANCE} and {@link #RESOURCE}. */
    PROJECT,
    /** The tenant-wide {@code _tenant} system project. Overrides {@link #RESOURCE}. */
    VANCE,
    /** A classpath resource under {@code vance-defaults/}. Outermost / fallback. */
    RESOURCE
}
