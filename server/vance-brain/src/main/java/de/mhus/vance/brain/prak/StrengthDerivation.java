package de.mhus.vance.brain.prak;

import de.mhus.vance.shared.prak.SpanStrength;
import java.util.Map;

/**
 * Sparse result of {@link SpanStrengthDeriver#derive}: the per-message
 * {@link SpanStrength} overrides for the analysed span.
 *
 * <p>Only entries that <em>differ</em> from the {@link SpanStrength#NORMAL}
 * default are included. Persistence is also sparse — the deriver removes
 * any prior {@code STRENGTH:*} tag on every message in the span first,
 * then writes the override tag for the WEAK / STRONG groups only.
 *
 * <p>{@link SpanStrength#PINNED} is never derived — only set explicitly
 * by user or recipe policy.
 */
public record StrengthDerivation(Map<String, SpanStrength> overrides) {

    public static StrengthDerivation empty() {
        return new StrengthDerivation(Map.of());
    }

    public boolean isEmpty() {
        return overrides.isEmpty();
    }
}
