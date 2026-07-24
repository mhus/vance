package de.mhus.vance.addon.brain.finance.tool;

import de.mhus.vance.addon.brain.finance.report.FinanceReportProcessor;
import de.mhus.vance.addon.brain.finance.report.FinanceReportRegistry;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** List the available finance report processors. */
@Component
public class FinanceReportProcessorsTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object", "properties", new LinkedHashMap<String, Object>());

    private final FinanceReportRegistry registry;

    public FinanceReportProcessorsTool(FinanceReportRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "finance_report_processors"; }

    @Override
    public String description() {
        return "List the available finance report processors (type, title, output kind) "
                + "for finance_report_generate.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() { return Set.of("eddie", "read", "document", "finance"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        List<Map<String, Object>> processors = new ArrayList<>();
        for (FinanceReportProcessor p : registry.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", p.type());
            m.put("title", p.title());
            m.put("outputKind", p.outputKind());
            processors.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processors", processors);
        return result;
    }
}
