package de.mhus.vance.brain.wonko;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What Wonko may do about a finding in one category. Ordered from loosest to
 * strictest — {@link #ordinal()} is the strictness.
 */
public enum MandateLevel {
    /** Wonko decides, records the decision with a reason and reports it. */
    DECIDE,
    /** Wonko proposes and asks for a confirmation. */
    CONFIRM,
    /** Wonko asks an open question. */
    ASK;

    public boolean isLooserThan(MandateLevel other) {
        return ordinal() < other.ordinal();
    }

    public static MandateLevel stricter(MandateLevel a, MandateLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** Case-insensitive parse; {@code null} for blank or unknown input. */
    public static @Nullable MandateLevel parse(@Nullable Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
