package de.mhus.vance.brain.prak;

/**
 * Coarse grouping of hot-path marker words by intent.
 *
 * <p>Downstream consumers (analyzer-prompt builder, span-strength
 * derivation) use the category to bias the cheap-tier analyzer call —
 * a {@link #FORGET} marker primes the prompt for revocation extraction,
 * a {@link #MEMORIZE} marker primes it for new-rule extraction.
 */
public enum MarkerCategory {

    /** "merk dir", "remember", "erinnere mich" — explicit memorize ask. */
    MEMORIZE,

    /** "vergiss", "forget" — explicit forget ask. */
    FORGET,

    /** "ab jetzt", "from now on", "in zukunft" — permanent rule going forward. */
    FUTURE_RULE,

    /** "jetzt", "right now" — punctual / session-scoped, not a long-term rule. */
    PUNCTUAL,

    /** "nicht mehr", "nie wieder" — explicit revocation of an existing rule. */
    REVOKE
}
