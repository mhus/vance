package de.mhus.vance.addon.brain.finance.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Collects every {@link FinanceReportProcessor} bean in the context and
 * exposes them by {@link FinanceReportProcessor#type()}. Mirrors the
 * {@code KindRegistry} pattern — Spring injects all processors, no central
 * enumeration.
 */
@Service
public class FinanceReportRegistry {

    private final List<FinanceReportProcessor> all;
    private final Map<String, FinanceReportProcessor> byType;

    public FinanceReportRegistry(List<FinanceReportProcessor> processors) {
        this.all = List.copyOf(processors);
        Map<String, FinanceReportProcessor> map = new LinkedHashMap<>();
        for (FinanceReportProcessor p : processors) {
            map.put(p.type(), p);
        }
        this.byType = Map.copyOf(map);
    }

    /** All registered processors, in bean-discovery order. */
    public List<FinanceReportProcessor> list() {
        return all;
    }

    /** The processor for {@code type}, or {@code null} when unknown. */
    public @Nullable FinanceReportProcessor find(String type) {
        return byType.get(type);
    }
}
