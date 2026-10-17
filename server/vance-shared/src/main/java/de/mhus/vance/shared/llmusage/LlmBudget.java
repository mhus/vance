package de.mhus.vance.shared.llmusage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * The cost budget one scope (the tenant, or one project) has set for chat
 * calls, read from the {@code ai.llm.budget.*} settings of exactly that scope
 * — never through the cascade. See {@link LlmBudgetService} for why.
 *
 * <p>Amounts are integer micro-units of {@link #currency}, like the ledger.
 * A limit of {@code 0} means "no limit in this window"; an unset, blank,
 * negative, out-of-range or unparsable setting reads as {@code 0} — all but
 * the unset and blank ones with a warning in the log. Unlimited is the
 * default, so a typo can only ever loosen a budget, never lock a tenant out.
 * A positive amount below one micro-unit is the smallest limit, not none.
 *
 * @param dailyMicros   limit per UTC day
 * @param monthlyMicros limit per UTC calendar month
 * @param totalMicros   limit from {@link #totalSince} until today
 * @param totalSince    first day the total budget counts; without it the
 *                      total limit does not apply ({@link #totalIncomplete()})
 * @param currency      ISO code the limits are in; only cost booked in this
 *                      currency counts
 * @param warnPercent   share of a limit at which a warning goes out,
 *                      {@code 1..99}; {@code 0} = no warnings, which is also
 *                      what any other value reads as (logged)
 */
@Slf4j
public record LlmBudget(
        long dailyMicros,
        long monthlyMicros,
        long totalMicros,
        @Nullable LocalDate totalSince,
        String currency,
        int warnPercent) {

    public static final String PREFIX = "ai.llm.budget.";
    public static final String KEY_DAILY = PREFIX + "daily";
    public static final String KEY_MONTHLY = PREFIX + "monthly";
    public static final String KEY_TOTAL = PREFIX + "total";
    public static final String KEY_TOTAL_SINCE = PREFIX + "total-since";
    public static final String KEY_CURRENCY = PREFIX + "currency";
    public static final String KEY_WARN_PERCENT = PREFIX + "warn-percent";

    public static final String DEFAULT_CURRENCY = "USD";
    public static final int DEFAULT_WARN_PERCENT = 80;

    /** Micro-units per unit of the currency, as a power of ten. */
    private static final int MICRO_DIGITS = 6;

    /**
     * Digits before the decimal point an amount may have. Twelve keeps the
     * micro-unit value well inside a {@code long}; nobody budgets a trillion.
     */
    static final int MAX_AMOUNT_INTEGER_DIGITS = 12;

    /** Longest amount text read at all — anything longer is not an amount. */
    static final int MAX_AMOUNT_LENGTH = 64;

    /** The budget of a scope that has set nothing. */
    public static final LlmBudget NONE = new LlmBudget(0, 0, 0, null, DEFAULT_CURRENCY, DEFAULT_WARN_PERCENT);

    /** Read a budget from the raw settings of one scope ({@code key → value}). */
    public static LlmBudget parse(Map<String, String> settings) {
        String currency = settings.get(KEY_CURRENCY);
        return new LlmBudget(
                amount(settings, KEY_DAILY),
                amount(settings, KEY_MONTHLY),
                amount(settings, KEY_TOTAL),
                date(settings, KEY_TOTAL_SINCE),
                currency == null || currency.isBlank()
                        ? DEFAULT_CURRENCY
                        : currency.trim().toUpperCase(Locale.ROOT),
                warnPercent(settings));
    }

    /** Whether any window carries a limit. A budget without one costs no ledger read. */
    public boolean hasLimit() {
        return dailyMicros > 0 || monthlyMicros > 0 || totalMicros > 0;
    }

    /** A total limit without a start date: set, but deliberately not enforced. */
    public boolean totalIncomplete() {
        return totalMicros > 0 && totalSince == null;
    }

    /** The limit of {@code window} in micro-units, {@code 0} when unlimited. */
    public long limitOf(BudgetWindow window) {
        return switch (window) {
            case DAILY -> dailyMicros;
            case MONTHLY -> monthlyMicros;
            case TOTAL -> totalSince == null ? 0 : totalMicros;
        };
    }

    /**
     * First day {@code window} counts, as seen on {@code today}, or
     * {@code null} when the window does not apply (no limit, no start date,
     * or a start date that has not arrived yet).
     */
    public @Nullable LocalDate windowStart(BudgetWindow window, LocalDate today) {
        if (limitOf(window) <= 0) {
            return null;
        }
        return switch (window) {
            case DAILY -> today;
            case MONTHLY -> today.withDayOfMonth(1);
            case TOTAL -> totalSince != null && !totalSince.isAfter(today) ? totalSince : null;
        };
    }

    private static long amount(Map<String, String> settings, String key) {
        String raw = settings.get(key);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String trimmed = raw.trim();
        BigDecimal value = trimmed.length() > MAX_AMOUNT_LENGTH ? null : parseDecimal(trimmed);
        if (value == null) {
            log.warn("LLM budget setting '{}' is not an amount: '{}' — treated as unlimited", key, abbreviate(trimmed));
            return 0;
        }
        if (value.signum() < 0) {
            // The setting form refuses this; a value written past it (raw
            // settings editor, an import) must not pass silently.
            log.warn("LLM budget setting '{}' is negative: '{}' — treated as unlimited", key, trimmed);
            return 0;
        }
        if (value.signum() == 0) {
            return 0;
        }
        // Digits before the point, read off precision and scale — never by
        // expanding the number: "1e999999999" is a valid BigDecimal whose
        // plain form would be a billion digits.
        long integerDigits = (long) value.precision() - value.scale();
        if (integerDigits > MAX_AMOUNT_INTEGER_DIGITS) {
            log.warn("LLM budget setting '{}' is out of range: '{}' — treated as unlimited", key, trimmed);
            return 0;
        }
        if (integerDigits < -MICRO_DIGITS) {
            // Far below one micro-unit. Positive still means "a limit": the
            // smallest one there is, never "unlimited".
            return 1;
        }
        long micros = value.setScale(MICRO_DIGITS, RoundingMode.HALF_UP)
                .movePointRight(MICRO_DIGITS)
                .longValueExact();
        return Math.max(1, micros);
    }

    private static @Nullable BigDecimal parseDecimal(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String abbreviate(String raw) {
        return raw.length() <= MAX_AMOUNT_LENGTH ? raw : raw.substring(0, MAX_AMOUNT_LENGTH) + "…";
    }

    private static @Nullable LocalDate date(Map<String, String> settings, String key) {
        String raw = settings.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.warn("LLM budget setting '{}' is not a date (yyyy-MM-dd): '{}' — total budget not applied", key, raw);
            return null;
        }
    }

    private static int warnPercent(Map<String, String> settings) {
        String raw = settings.get(KEY_WARN_PERCENT);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_WARN_PERCENT;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("LLM budget setting '{}' is not a percentage: '{}' — warnings off", KEY_WARN_PERCENT, raw);
            return 0;
        }
        if (value == 0) {
            return 0;
        }
        if (value < 0 || value >= 100) {
            // At 100 % the refusal itself is the message, so a threshold there
            // or beyond can never fire. Said out loud rather than capped: an
            // admin who typed 100 learns that it means "no warning".
            log.warn("LLM budget setting '{}' is {} — must be 1..99, warnings off", KEY_WARN_PERCENT, raw.trim());
            return 0;
        }
        return value;
    }
}
