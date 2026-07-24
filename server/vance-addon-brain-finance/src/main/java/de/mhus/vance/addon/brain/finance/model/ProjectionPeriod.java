package de.mhus.vance.addon.brain.finance.model;

/**
 * One bucket of a {@link FinanceProjection} — a half-open date range
 * {@code [from, to)} with a granularity-specific display {@code label}
 * (e.g. {@code 2026-03} for a month, {@code 2026} for a year, the ISO date
 * for a day/week). Dates are ISO {@code yyyy-MM-dd}.
 */
public record ProjectionPeriod(String label, String from, String to) {}
