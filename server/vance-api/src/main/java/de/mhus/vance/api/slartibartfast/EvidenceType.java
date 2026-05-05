package de.mhus.vance.api.slartibartfast;

/**
 * Provenance of an {@link EvidenceSource}. Used by validators to
 * weigh how authoritative a citation is — a {@link #USER}-stated
 * preference outweighs a {@link #DEFAULT} fallback for goal-tied
 * decisions, while {@link #MANUAL} content is the prime authority
 * for stylistic constraints.
 */
public enum EvidenceType {
    /** Read from a {@code manuals/...} document via {@code manual_read}. */
    MANUAL,

    /** Direct user statement in the spawn-input. */
    USER,

    /** Description string from another recipe consulted during
     *  planning (e.g. picking which sub-recipe to allow). */
    RECIPE_DESCRIPTION,

    /** Engine-supplied default — used as a last-resort source when
     *  neither user nor manual covers a slot. Always weakest. */
    DEFAULT,
}
