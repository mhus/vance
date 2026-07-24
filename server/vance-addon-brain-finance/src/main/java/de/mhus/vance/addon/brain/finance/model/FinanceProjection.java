package de.mhus.vance.addon.brain.finance.model;

import java.util.List;

/**
 * A time-range projection table — {@code periods} (columns) and {@code rows}
 * (one per node, pre-order). Produced on-demand by
 * {@link de.mhus.vance.addon.brain.finance.FinanceProjector}; never persisted
 * (a persisted view is a report document produced by a processor).
 */
public record FinanceProjection(
        List<ProjectionPeriod> periods,
        List<ProjectionRow> rows) {}
