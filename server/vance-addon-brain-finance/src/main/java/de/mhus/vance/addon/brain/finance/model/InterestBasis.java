package de.mhus.vance.addon.brain.finance.model;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * German banking percentage-calculation basis for a {@link FinanceInterest}.
 *
 * <ul>
 *   <li>{@link #VOM_HUNDERT} — percent <em>of</em> the base (the standard
 *       case, v1). {@code interest = base × p/100}.</li>
 *   <li>{@link #IM_HUNDERT} — percent <em>contained in</em> the gross
 *       (discount/Skonto style). Reserved for v2.</li>
 *   <li>{@link #AUF_HUNDERT} — percent <em>added on top</em> to reach the
 *       gross. Reserved for v2.</li>
 * </ul>
 *
 * v1 implements {@link #VOM_HUNDERT}; the other two round-trip through the
 * codec but the math service treats them as {@code VOM_HUNDERT} until v2.
 */
public enum InterestBasis {
    VOM_HUNDERT,
    IM_HUNDERT,
    AUF_HUNDERT;

    /** Lower-case discriminator as written to disk ({@code basis:}). */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive parse; {@code fallback} when unknown/blank. */
    public static InterestBasis parse(@Nullable String s, InterestBasis fallback) {
        if (s == null || s.isBlank()) return fallback;
        String v = s.trim();
        for (InterestBasis b : values()) {
            if (b.name().equalsIgnoreCase(v)) return b;
        }
        return fallback;
    }
}
