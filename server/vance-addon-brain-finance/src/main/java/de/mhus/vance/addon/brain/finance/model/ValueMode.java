package de.mhus.vance.addon.brain.finance.model;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Kind of a {@link FinanceValue} record.
 *
 * <ul>
 *   <li>{@link #RECURRING} — a rate/flow (e.g. {@code 50 EUR / 3 months}).
 *       Requires a {@link Period}; contributes to the annual snapshot rate.</li>
 *   <li>{@link #ONE_TIME} — a single lump at a date ({@code validFrom}).
 *       Has no per-year rate — excluded from the annual snapshot (shown
 *       separately as a one-time sum) and landing in the projection only in
 *       the period that contains its date.</li>
 * </ul>
 */
public enum ValueMode {
    RECURRING,
    ONE_TIME;

    /** Lower-case discriminator as written to disk ({@code mode:}). */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive parse; {@code fallback} when unknown/blank. */
    public static ValueMode parse(@Nullable String s, ValueMode fallback) {
        if (s == null || s.isBlank()) return fallback;
        String v = s.trim();
        for (ValueMode m : values()) {
            if (m.name().equalsIgnoreCase(v)) return m;
        }
        return fallback;
    }
}
