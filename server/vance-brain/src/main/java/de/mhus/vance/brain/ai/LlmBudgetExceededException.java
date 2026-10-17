package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.llmusage.BudgetScope;
import de.mhus.vance.shared.llmusage.LlmBudgetService;
import de.mhus.vance.shared.llmusage.LlmUsageService;
import dev.langchain4j.exception.NonRetriableException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A chat call refused before it reached the provider, because a cost budget
 * of its tenant or project is exhausted.
 *
 * <p>Extends langchain4j's {@link NonRetriableException} on purpose. The
 * resilience layer would otherwise match the word "budget"/"quota" style
 * text against its transient-error patterns and retry a refusal five times;
 * the typed marker makes it advance to the next chain entry instead, which
 * may be a free local model the gate admits.
 *
 * <p>The message is the user-facing explanation: it names the level, the
 * window and the setting an admin raises. A project refusal also says what was
 * used against what limit. A tenant refusal does not: it reaches every session
 * of the tenant, while the tenant's spend is for tenant admins only (the same
 * line {@code GET /usage/budget} draws), so it carries no amounts.
 */
public class LlmBudgetExceededException extends NonRetriableException {

    private final transient LlmBudgetService.WindowStatus denial;

    public LlmBudgetExceededException(LlmBudgetService.WindowStatus denial) {
        super(messageOf(denial));
        this.denial = denial;
    }

    /** The exhausted window that caused the refusal. */
    public LlmBudgetService.WindowStatus denial() {
        return denial;
    }

    static String messageOf(LlmBudgetService.WindowStatus d) {
        String level =
                switch (d.scope()) {
                    case TENANT -> "Tenant";
                    case PROJECT -> "Project '" + d.scopeId() + "'";
                };
        String period =
                switch (d.window()) {
                    case DAILY -> "today";
                    case MONTHLY -> "this month";
                    case TOTAL -> "since " + d.from();
                };
        String admin =
                switch (d.scope()) {
                    case TENANT -> "a tenant admin";
                    case PROJECT -> "a project admin";
                };
        String raise =
                " Ask " + admin + " to raise 'ai.llm.budget." + d.window().label() + "'.";
        if (d.scope() == BudgetScope.TENANT) {
            return level + " LLM budget exhausted " + period + "." + raise;
        }
        // Cent precision unless a limit below one cent would read as "0.00".
        int scale = d.limitMicros() < 10_000 ? 6 : 2;
        return level + " LLM budget exhausted: " + amount(d.usedMicros(), scale) + " of "
                + amount(d.limitMicros(), scale) + " "
                + d.currency() + " " + period + "." + raise;
    }

    private static String amount(long micros, int scale) {
        return BigDecimal.valueOf(LlmUsageService.fromMicros(micros))
                .setScale(scale, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
