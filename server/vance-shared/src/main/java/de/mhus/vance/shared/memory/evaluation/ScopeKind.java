package de.mhus.vance.shared.memory.evaluation;

/**
 * Scope level of an extracted item — controls where the resulting
 * memory entry is anchored when promoted.
 */
public enum ScopeKind {

    /** Tenant-wide — applies to every project the user touches. Rare. */
    GLOBAL,

    /** Project-wide — typical for codebase observations and user preferences within a project. */
    PROJECT,

    /** Session-scoped — short-lived, punctual instructions ("right now don't commit"). */
    SESSION,

    /** Bound to a specific task or sub-process. Rare. */
    TASK
}
