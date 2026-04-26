package de.mhus.vance.shared.memory;

/**
 * Category of a {@link MemoryDocument}. The container shape is the
 * same; the kind drives how a consumer surfaces or filters it.
 *
 * <p>v1 names — extend cautiously: an enum value committed to the DB
 * cannot easily disappear without a migration. Add new kinds, rather
 * than renaming or removing.
 */
public enum MemoryKind {

    /**
     * Compaction summary of older chat turns. Carries refs to the
     * archived {@code ChatMessageDocument}s so the original prose
     * stays auditable. The engine's history-replay treats one
     * {@code ARCHIVED_CHAT} record as a substitute for the entries
     * it references.
     */
    ARCHIVED_CHAT,

    /** Engine-internal scratchpad notes — drafts, intermediate findings. */
    SCRATCHPAD,

    /**
     * Active or historical plan. The latest non-superseded {@code PLAN}
     * entry per scope is the current one; superseded chains form the
     * plan's history.
     */
    PLAN,

    /**
     * Derived knowledge produced by an engine — typically
     * project-scoped, feeds the future Knowledge Graph.
     */
    INSIGHT,

    /** Catch-all for engine-specific records that don't fit the above. */
    OTHER
}
