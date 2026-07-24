package de.mhus.vance.addon.brain.finance.model;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Time unit of a {@link Period}. Each unit carries its length in days on a
 * fixed 365-day year — the reconciliation invariant of the finance model:
 * {@code 12 months = 52 weeks = 365 days}, so a month is {@code 365/12 =
 * 30.4166…} days and a week is {@code 365/52 = 7.0192…} days. These
 * constants are intrinsic to the model, hence they live on the enum; the
 * math service divides/multiplies through them to normalise everything onto
 * a per-year canonical rate.
 */
public enum PeriodUnit {
    DAY(1.0),
    WEEK(365.0 / 52.0),
    MONTH(365.0 / 12.0),
    YEAR(365.0);

    private final double days;

    PeriodUnit(double days) {
        this.days = days;
    }

    /** Length of one unit in days on the fixed 365-day year. */
    public double days() {
        return days;
    }

    /** Lower-case discriminator as written to disk ({@code unit:}). */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive parse; {@code null} when unknown/blank. */
    public static @Nullable PeriodUnit parse(@Nullable String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        for (PeriodUnit u : values()) {
            if (u.name().equalsIgnoreCase(v)) return u;
        }
        return null;
    }
}
