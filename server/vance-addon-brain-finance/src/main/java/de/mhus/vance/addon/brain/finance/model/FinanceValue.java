package de.mhus.vance.addon.brain.finance.model;

import org.jspecify.annotations.Nullable;

/**
 * One additive value record of a {@link FinanceNode}. The base {@code value}
 * plus an optional {@link FinanceInterest} pair; {@code mode} decides whether
 * it is a recurring rate or a one-time lump.
 *
 * <ul>
 *   <li>{@link ValueMode#RECURRING}: {@code period} is required and defines
 *       the rate ({@code value per period}). {@code validFrom}/{@code validTo}
 *       (ISO {@code yyyy-MM-dd}, both optional) bound when the rate applies.</li>
 *   <li>{@link ValueMode#ONE_TIME}: {@code validFrom} is the date of the lump
 *       (required); {@code period} is ignored.</li>
 * </ul>
 *
 * {@code sign} is an optional per-record escape hatch ({@code +1}/{@code -1});
 * {@code null} means {@code +1} (the node's own {@code sign} still applies to
 * the whole node contribution).
 */
public record FinanceValue(
        double value,
        ValueMode mode,
        @Nullable Period period,
        @Nullable String validFrom,
        @Nullable String validTo,
        @Nullable Integer sign,
        @Nullable FinanceInterest interest) {}
