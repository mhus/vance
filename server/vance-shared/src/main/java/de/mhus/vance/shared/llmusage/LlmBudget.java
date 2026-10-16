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
 * negative or unparsable setting reads as {@code 0}. Unlimited is the
 * default, so a typo can only ever loosen a budget, never lock a tenant out.
 *
 * @param dailyMicros   limit per UTC day
 * @param monthlyMicros limit per UTC calendar month
 * @param totalMicros   limit from {@link #totalSince} until today
 * @param totalSince    first day the total budget counts; without it the
 *                      total limit does not apply ({@link #totalIncomplete()})
 * @param currency      ISO code the limits are in; only cost booked in this
 *                      currency counts
 * @param warnPercent   share of a limit at which a warning goes out,
 *                      {@code 0} = no warnings
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
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.signum() <= 0) {
                return 0;
            }
            return value.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            log.warn("LLM budget setting '{}' is not an amount: '{}' — treated as unlimited", key, raw);
            return 0;
        }
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
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 && value < 100 ? value : 0;
        } catch (NumberFormatException e) {
            log.warn("LLM budget setting '{}' is not a percentage: '{}' — warnings off", KEY_WARN_PERCENT, raw);
            return 0;
        }
    }
}
