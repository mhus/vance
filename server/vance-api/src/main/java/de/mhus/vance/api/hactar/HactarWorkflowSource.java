package de.mhus.vance.api.hactar;

/**
 * Cascade tier that provides a resolved workflow. Unlike recipes there
 * is intentionally no resource tier — workflows are always project- or
 * tenant-specific (plan §12).
 */
public enum HactarWorkflowSource {
    /** Stored under the current project's document layer. */
    PROJECT,
    /** Stored under the tenant-shared {@code _vance} project. */
    TENANT
}
