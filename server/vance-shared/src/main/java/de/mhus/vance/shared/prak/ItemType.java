package de.mhus.vance.shared.prak;

/**
 * Classification axis for an {@link ExtractedItem}.
 *
 * <p>The asymmetry is load-bearing: {@link #FACT}s are verifiable
 * against the world and may be auto-promoted; {@link #INSTRUCTION}s
 * come from the user and must be confirmed before becoming a durable
 * rule ("can't get rid of it" trap); {@link #PREFERENCE}s are softer
 * tendencies that decay over time.
 */
public enum ItemType {

    /** Declarative observation ("X uses Y"). Auto-promote OK. */
    FACT,

    /** Imperative directive ("do X", "never Y"). Inbox-offer only. */
    INSTRUCTION,

    /** Soft tendency ("likes terse replies"). Auto-promote, low strength, decays. */
    PREFERENCE
}
