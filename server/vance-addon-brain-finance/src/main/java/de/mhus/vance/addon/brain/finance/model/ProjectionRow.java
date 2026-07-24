package de.mhus.vance.addon.brain.finance.model;

import java.util.List;

/**
 * One row of a {@link FinanceProjection} — a node's sign-applied amount per
 * period, aligned by index to {@link FinanceProjection#periods()}, plus the
 * {@code total} across the whole range.
 */
public record ProjectionRow(String name, List<Double> amounts, double total) {}
