package de.mhus.vance.shared.llmusage;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.insights.LlmBudgetStatusDto;
import de.mhus.vance.api.insights.LlmBudgetWindowDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link LlmBudgetMapper}: micro-units become amounts here, and the flags say one thing each. */
class LlmBudgetMapperTest {

    @Test
    void window_convertsMicroUnitsToAmounts() {
        LlmBudgetWindowDto dto = LlmBudgetMapper.toDto(window(2_500_000, 1_250_000, Map.of()));

        assertThat(dto.getWindow()).isEqualTo("daily");
        assertThat(dto.getFrom()).isEqualTo("2026-10-16");
        assertThat(dto.getLimit()).isEqualTo(2.5);
        assertThat(dto.getUsed()).isEqualTo(1.25);
        assertThat(dto.getPercent()).isEqualTo(50.0);
        assertThat(dto.isExhausted()).isFalse();
        assertThat(dto.isWarning()).isFalse();
        assertThat(dto.getUnpricedCalls()).isEqualTo(4);
    }

    @Test
    void warning_onlyWhileNotExhausted() {
        LlmBudgetWindowDto warned = LlmBudgetMapper.toDto(window(1_000_000, 900_000, Map.of()));
        LlmBudgetWindowDto exhausted = LlmBudgetMapper.toDto(window(1_000_000, 1_200_000, Map.of()));

        assertThat(warned.isWarning()).isTrue();
        assertThat(warned.isExhausted()).isFalse();
        // Past the warn threshold too, but "exhausted" is the one thing to say.
        assertThat(exhausted.isExhausted()).isTrue();
        assertThat(exhausted.isWarning()).isFalse();
        assertThat(exhausted.getPercent()).isEqualTo(120.0);
    }

    @Test
    void uncounted_isSortedByCurrency_andConverted() {
        LlmBudgetWindowDto dto =
                LlmBudgetMapper.toDto(window(1_000_000, 0, Map.of("USD", 3_000_000L, "CHF", 500_000L, "GBP", 1L)));

        assertThat(dto.getUncounted().keySet()).containsExactly("CHF", "GBP", "USD");
        assertThat(dto.getUncounted())
                .containsEntry("CHF", 0.5)
                .containsEntry("GBP", 0.000001)
                .containsEntry("USD", 3.0);
    }

    @Test
    void scope_carriesBudgetFacts_andItsWindows() {
        LlmBudget budget = LlmBudget.parse(Map.of(
                LlmBudget.KEY_DAILY, "2.5",
                LlmBudget.KEY_TOTAL, "100",
                LlmBudget.KEY_CURRENCY, "eur",
                LlmBudget.KEY_WARN_PERCENT, "70"));
        LlmBudgetService.ScopeStatus status = new LlmBudgetService.ScopeStatus(
                BudgetScope.PROJECT, "research", budget, List.of(window(2_500_000, 0, Map.of())));

        LlmBudgetStatusDto dto = LlmBudgetMapper.toDto(status);

        assertThat(dto.getScope()).isEqualTo("project");
        assertThat(dto.getScopeId()).isEqualTo("research");
        assertThat(dto.getCurrency()).isEqualTo("EUR");
        assertThat(dto.getWarnPercent()).isEqualTo(70);
        // A total without a start date is shown, flagged, and not enforced.
        assertThat(dto.isTotalIncomplete()).isTrue();
        assertThat(dto.getWindows())
                .singleElement()
                .satisfies(w -> assertThat(w.getLimit()).isEqualTo(2.5));
    }

    @Test
    void unlimitedScope_hasNoWindows() {
        LlmBudgetStatusDto dto = LlmBudgetMapper.toDto(
                new LlmBudgetService.ScopeStatus(BudgetScope.TENANT, "_tenant", LlmBudget.NONE, List.of()));

        assertThat(dto.getScope()).isEqualTo("tenant");
        assertThat(dto.getWindows()).isEmpty();
        assertThat(dto.isTotalIncomplete()).isFalse();
    }

    private static LlmBudgetService.WindowStatus window(long limit, long used, Map<String, Long> uncounted) {
        return new LlmBudgetService.WindowStatus(
                BudgetScope.PROJECT,
                "research",
                BudgetWindow.DAILY,
                LocalDate.of(2026, 10, 16),
                limit,
                used,
                "USD",
                80,
                4,
                uncounted);
    }
}
