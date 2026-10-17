package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.llmusage.BudgetScope;
import de.mhus.vance.shared.llmusage.BudgetWindow;
import de.mhus.vance.shared.llmusage.LlmBudgetService;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The refusal text is what the user reads — it has to name the level, the numbers and the setting. */
class LlmBudgetExceededExceptionTest {

    @Test
    void message_namesTenantMonthAndSetting_withoutAmounts() {
        // Reaches every session of the tenant; the tenant's spend is for tenant admins only.
        String message = LlmBudgetExceededException.messageOf(
                status(BudgetScope.TENANT, "_tenant", BudgetWindow.MONTHLY, 50_000_000, 50_120_000));

        assertThat(message)
                .isEqualTo("Tenant LLM budget exhausted this month."
                        + " Ask a tenant admin to raise 'ai.llm.budget.monthly'.");
        assertThat(message).doesNotContain("50").doesNotContain("USD");
    }

    @Test
    void message_namesProjectAmounts_atCentPrecision() {
        String message = LlmBudgetExceededException.messageOf(
                status(BudgetScope.PROJECT, "research", BudgetWindow.MONTHLY, 50_000_000, 50_120_000));

        assertThat(message)
                .isEqualTo("Project 'research' LLM budget exhausted: 50.12 of 50.00 USD this month."
                        + " Ask a project admin to raise 'ai.llm.budget.monthly'.");
    }

    @Test
    void message_keepsSubCentLimitsReadable() {
        String message = LlmBudgetExceededException.messageOf(
                status(BudgetScope.PROJECT, "research", BudgetWindow.DAILY, 1, 245));

        assertThat(message)
                .startsWith("Project 'research' LLM budget exhausted: 0.000245 of 0.000001 USD today.")
                .endsWith("Ask a project admin to raise 'ai.llm.budget.daily'.");
    }

    @Test
    void message_namesTheStartOfATotalBudget() {
        String message = LlmBudgetExceededException.messageOf(
                status(BudgetScope.PROJECT, "grant", BudgetWindow.TOTAL, 500_000_000, 501_000_000));

        assertThat(message).contains("USD since 2026-10-01.");
    }

    private static LlmBudgetService.WindowStatus status(
            BudgetScope scope, String scopeId, BudgetWindow window, long limit, long used) {
        return new LlmBudgetService.WindowStatus(
                scope, scopeId, window, LocalDate.of(2026, 10, 1), limit, used, "USD", 80, 0, Map.of());
    }
}
