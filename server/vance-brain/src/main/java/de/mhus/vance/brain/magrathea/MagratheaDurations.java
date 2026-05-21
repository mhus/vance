package de.mhus.vance.brain.magrathea;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses workflow YAML duration values (plan §4.5). Accepts ISO-8601
 * ({@code PT7D}, {@code PT5M30S}) and the convenience shortcuts
 * {@code 7d}, {@code 4h}, {@code 30m}, {@code 45s}.
 *
 * <p>Negative or zero durations are valid — they fire immediately
 * (the scanner sees {@code fireAt <= now} on its first scan).
 */
public final class MagratheaDurations {

    private static final Pattern SHORTCUT = Pattern.compile("(?i)^\\s*(\\d+)\\s*(d|h|m|s|ms)\\s*$");

    private MagratheaDurations() {}

    /**
     * @throws IllegalArgumentException when the input is null, blank
     *         or does not match a recognised form.
     */
    public static Duration parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("duration string is required");
        }
        String trimmed = input.trim();
        Matcher m = SHORTCUT.matcher(trimmed);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            return switch (m.group(2).toLowerCase()) {
                case "d"  -> Duration.ofDays(n);
                case "h"  -> Duration.ofHours(n);
                case "m"  -> Duration.ofMinutes(n);
                case "s"  -> Duration.ofSeconds(n);
                case "ms" -> Duration.ofMillis(n);
                default -> throw new IllegalArgumentException(
                        "unreachable duration unit: " + m.group(2));
            };
        }
        try {
            return Duration.parse(trimmed);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "duration '" + input + "' is neither ISO-8601 nor a shortcut "
                            + "(e.g. '7d', '30m', '45s')");
        }
    }
}
