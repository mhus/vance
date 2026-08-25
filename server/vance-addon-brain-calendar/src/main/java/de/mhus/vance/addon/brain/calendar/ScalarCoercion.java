package de.mhus.vance.addon.brain.calendar;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import org.jspecify.annotations.Nullable;

/**
 * Scalar coercion shared by the {@code calendar} and {@code timeline}
 * codecs. Both store temporal values as <b>strings</b> so they
 * round-trip verbatim regardless of source format, and both have to
 * survive the same YAML hazard: SnakeYAML's tag resolver silently
 * promotes an unquoted ISO-8601 date like {@code 2026-07-15} to a
 * {@link Date}, and a bare {@code 201.4} to a {@link Double}. Without
 * coercion the value would not be a string and the entry would be
 * dropped as malformed.
 *
 * <p>Package-private utility rather than a copy per codec — the two
 * codecs must agree, otherwise the same YAML line means different
 * things depending on which kind reads it.
 */
final class ScalarCoercion {

    private ScalarCoercion() {
        // utility class
    }

    /**
     * Coerce a YAML/JSON scalar to a non-blank string, or {@code null}
     * when the value is absent / blank / a structure.
     *
     * <p>For {@link Date} we emit a UTC ISO-8601 string; a
     * midnight-UTC stamp (the shape SnakeYAML produces for date-only
     * inputs) is stripped to {@code yyyy-MM-dd}. That biases towards
     * the common case — users who deliberately want a literal
     * "midnight UTC" timestamp should quote the value to keep it a
     * string.
     */
    static @Nullable String coerceToString(@Nullable Object raw) {
        if (raw == null) return null;
        if (raw instanceof String s) return s.isBlank() ? null : s;
        if (raw instanceof Date d) {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            String s = fmt.format(d);
            if (s.endsWith("T00:00:00Z") || s.endsWith("T00:00:00+00:00")) {
                return s.substring(0, 10);
            }
            return s;
        }
        String s = raw.toString();
        return s.isBlank() ? null : s;
    }

    /**
     * Render a stored scalar back for serialisation: a string that is
     * a plain number is emitted as a number so YAML gets
     * {@code from: 201.4} instead of {@code from: '201.4'}.
     *
     * <p>Integral literals become {@link Long} / {@link BigInteger},
     * decimals {@link BigDecimal}. The split matters: SnakeYAML tags a
     * {@link BigDecimal} as a float and emits {@code 1969.0} for a
     * whole number, so {@code from: 5} would come back as {@code 5.0}
     * on every save. {@link BigDecimal} keeps the literal digits for
     * decimals, so {@code 201.40} stays {@code 201.40} — the
     * round-trip is textual, not numeric.
     *
     * <p>Anything that is not a plain number (an ISO timestamp, a
     * label) passes through as the string it is.
     */
    static Object numberOrString(String stored) {
        String t = stored.trim();
        if (INTEGRAL.matcher(t).matches()) {
            try {
                return Long.valueOf(t);
            } catch (NumberFormatException e) {
                return new BigInteger(t);
            }
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return stored;
        }
    }

    private static final java.util.regex.Pattern INTEGRAL =
            java.util.regex.Pattern.compile("-?\\d+");
}
