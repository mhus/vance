package de.mhus.vance.shared.llmusage;

import de.mhus.vance.api.insights.LlmBudgetStatusDto;
import de.mhus.vance.api.insights.LlmBudgetWindowDto;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** {@link LlmBudgetService} results → wire DTOs. Micro-units become plain amounts here and only here. */
public final class LlmBudgetMapper {

    private LlmBudgetMapper() {}

    public static LlmBudgetStatusDto toDto(LlmBudgetService.ScopeStatus status) {
        return LlmBudgetStatusDto.builder()
                .scope(status.scope().label())
                .scopeId(status.scopeId())
                .currency(status.budget().currency())
                .warnPercent(status.budget().warnPercent())
                .totalIncomplete(status.budget().totalIncomplete())
                .windows(status.windows().stream().map(LlmBudgetMapper::toDto).toList())
                .build();
    }

    public static LlmBudgetWindowDto toDto(LlmBudgetService.WindowStatus w) {
        Map<String, Double> uncounted = new LinkedHashMap<>();
        new TreeMap<>(w.uncountedMicros())
                .forEach((currency, micros) -> uncounted.put(currency, LlmUsageService.fromMicros(micros)));
        return LlmBudgetWindowDto.builder()
                .window(w.window().label())
                .from(w.from().toString())
                .limit(LlmUsageService.fromMicros(w.limitMicros()))
                .used(LlmUsageService.fromMicros(w.usedMicros()))
                .percent(w.percent())
                .exhausted(w.exhausted())
                .warning(!w.exhausted() && w.warning())
                .unpricedCalls(w.unpricedCalls())
                .uncounted(uncounted)
                .build();
    }
}
