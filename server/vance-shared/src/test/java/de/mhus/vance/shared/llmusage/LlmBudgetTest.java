package de.mhus.vance.shared.llmusage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmBudget#parse} — every bad input has to loosen the budget, never
 * tighten it: unlimited is the default, and a typo must not lock a tenant out.
 */
class LlmBudgetTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    @Test
    void amounts_areReadAsExactMicroUnits() {
        LlmBudget b = LlmBudget.parse(Map.of(
                LlmBudget.KEY_DAILY, "2.50",
                LlmBudget.KEY_MONTHLY, "0.1",
                LlmBudget.KEY_TOTAL, "0.0000015"));

        assertThat(b.dailyMicros()).isEqualTo(2_500_000);
        // 0.1 is not exact in binary floating point — BigDecimal keeps it exact.
        assertThat(b.monthlyMicros()).isEqualTo(100_000);
        assertThat(b.totalMicros()).isEqualTo(2);
    }

    @Test
    void blankNegativeAndGarbage_readAsUnlimited() {
        LlmBudget b = LlmBudget.parse(Map.of(
                LlmBudget.KEY_DAILY, "  ",
                LlmBudget.KEY_MONTHLY, "-5",
                LlmBudget.KEY_TOTAL, "five euros"));

        assertThat(b.hasLimit()).isFalse();
    }

    @Test
    void currency_defaultsToUsd_andIsNormalized() {
        assertThat(LlmBudget.parse(Map.of()).currency()).isEqualTo("USD");
        assertThat(LlmBudget.parse(Map.of(LlmBudget.KEY_CURRENCY, " eur ")).currency())
                .isEqualTo("EUR");
    }

    @Test
    void warnPercent_defaultsTo80_andOutOfRangeSwitchesWarningsOff() {
        assertThat(LlmBudget.parse(Map.of()).warnPercent()).isEqualTo(80);
        assertThat(LlmBudget.parse(Map.of(LlmBudget.KEY_WARN_PERCENT, "95")).warnPercent())
                .isEqualTo(95);
        assertThat(LlmBudget.parse(Map.of(LlmBudget.KEY_WARN_PERCENT, "0")).warnPercent())
                .isZero();
        assertThat(LlmBudget.parse(Map.of(LlmBudget.KEY_WARN_PERCENT, "100")).warnPercent())
                .isZero();
        assertThat(LlmBudget.parse(Map.of(LlmBudget.KEY_WARN_PERCENT, "lots")).warnPercent())
                .isZero();
    }

    @Test
    void windows_startAtTodayAndTheFirstOfTheMonth() {
        LlmBudget b = LlmBudget.parse(Map.of(LlmBudget.KEY_DAILY, "1", LlmBudget.KEY_MONTHLY, "10"));

        assertThat(b.windowStart(BudgetWindow.DAILY, TODAY)).isEqualTo(TODAY);
        assertThat(b.windowStart(BudgetWindow.MONTHLY, TODAY)).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(b.windowStart(BudgetWindow.TOTAL, TODAY)).isNull();
    }

    @Test
    void total_withoutStartDate_isIncomplete_andNotApplied() {
        LlmBudget b = LlmBudget.parse(Map.of(LlmBudget.KEY_TOTAL, "500"));

        assertThat(b.totalIncomplete()).isTrue();
        assertThat(b.limitOf(BudgetWindow.TOTAL)).isZero();
        assertThat(b.windowStart(BudgetWindow.TOTAL, TODAY)).isNull();
    }

    @Test
    void total_appliesFromItsStartDate_notBefore() {
        LlmBudget started =
                LlmBudget.parse(Map.of(LlmBudget.KEY_TOTAL, "500", LlmBudget.KEY_TOTAL_SINCE, "2026-09-01"));
        LlmBudget future = LlmBudget.parse(Map.of(LlmBudget.KEY_TOTAL, "500", LlmBudget.KEY_TOTAL_SINCE, "2026-11-01"));
        LlmBudget badDate = LlmBudget.parse(Map.of(LlmBudget.KEY_TOTAL, "500", LlmBudget.KEY_TOTAL_SINCE, "1.11.2026"));

        assertThat(started.windowStart(BudgetWindow.TOTAL, TODAY)).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(future.windowStart(BudgetWindow.TOTAL, TODAY)).isNull();
        assertThat(badDate.totalIncomplete()).isTrue();
    }
}
