package de.mhus.vance.addon.brain.finance.model;

/**
 * A duration expressed as {@code count × unit} (e.g. {@code 3 MONTH}).
 * Length in days on the fixed 365-day year is {@code count × unit.days()}.
 */
public record Period(int count, PeriodUnit unit) {

    /** Length of this period in days on the fixed 365-day year. */
    public double days() {
        return count * unit.days();
    }

    /** Fraction of a year this period spans. */
    public double years() {
        return days() / PeriodUnit.YEAR.days();
    }
}
