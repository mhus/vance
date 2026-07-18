package de.mhus.vance.brain.damogran;

/**
 * Terminal status of a single task or a whole compose run.
 *
 * <p>Deliberately binary: Damogran is linear and has no branch/gate vocabulary
 * (that is Vogon / Magrathea territory). Errors are carried in the result's
 * {@code error} field, not routed via status variants.
 */
public enum DamogranStatus {
    SUCCESS,
    FAILURE
}
