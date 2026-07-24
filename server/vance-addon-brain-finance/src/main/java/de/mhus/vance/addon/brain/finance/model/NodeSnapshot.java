package de.mhus.vance.addon.brain.finance.model;

/**
 * Computed snapshot values for one {@link FinanceNode} — the sign-applied,
 * bottom-up-rolled result written to {@code $computed.nodes[name]}. Derived
 * data, never a source of truth (regenerable from the raw tree).
 *
 * <p>{@code perYear} is the canonical recurring rate ({@code base + interest},
 * both sign-applied); {@code perMonth}/{@code perWeek}/{@code perDay} are its
 * fixed-year display conversions. {@code oneTimeSum} is the sign-applied total
 * of {@link ValueMode#ONE_TIME} records in the subtree — held out of the rate.
 */
public record NodeSnapshot(
        String name,
        double perYear,
        double perMonth,
        double perWeek,
        double perDay,
        double base,
        double interest,
        double oneTimeSum) {}
