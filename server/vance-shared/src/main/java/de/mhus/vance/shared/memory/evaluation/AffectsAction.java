package de.mhus.vance.shared.memory.evaluation;

/**
 * Effect of an item on <em>existing</em> memory entries it references
 * via {@link AffectsExisting#targetRef()}.
 */
public enum AffectsAction {

    /** Replace target — target becomes superseded, this item is the successor. */
    SUPERSEDE,

    /** Remove target without a successor (explicit revocation). */
    REVOKE,

    /** Add information to target without invalidating it. */
    EXTEND,

    /** Sharpen / re-phrase target with the same intent. */
    REFINE
}
