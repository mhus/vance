package de.mhus.vance.brain.milliways;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a handler reports back. {@code message} is the single line the user
 * reads; {@code details} is the machine-readable version that ends up in
 * the audit entry.
 *
 * <p>The handler id is not part of this — {@link MilliwaysService} knows
 * which handler it called and stamps it into the wire DTO.
 */
public record ShareResult(String message, Map<String, Object> details) {

    public ShareResult {
        details = Map.copyOf(details);
    }

    public static ShareResult of(String message) {
        return new ShareResult(message, Map.of());
    }

    /** Detail map that keeps insertion order, for readable audit entries. */
    public static Map<String, Object> newDetails() {
        return new LinkedHashMap<>();
    }
}
