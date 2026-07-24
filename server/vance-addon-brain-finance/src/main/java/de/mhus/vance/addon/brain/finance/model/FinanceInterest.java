package de.mhus.vance.addon.brain.finance.model;

/**
 * Interest attached to a {@link FinanceValue} — tracked separately from the
 * base amount so a report can distinguish base vs. interest. {@code rate} is
 * a percentage (e.g. {@code 5.0} = 5%) per {@code period} (e.g. {@code 1
 * YEAR}). {@code compound} controls whether the projection compounds
 * ({@code base × (1+rate)^n}); the annual snapshot always treats interest
 * linearly regardless.
 */
public record FinanceInterest(
        double rate,
        Period period,
        InterestBasis basis,
        boolean compound) {}
