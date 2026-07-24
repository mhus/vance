package de.mhus.vance.addon.brain.finance.tool;

import de.mhus.vance.addon.brain.finance.FinanceService;
import de.mhus.vance.addon.brain.finance.FinanceTreeCodec;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Replace a node's value records. */
@Component
public class FinanceNodeValueSetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string"));
                put("name", Map.of("type", "string", "description", "Node to set values on."));
                put("values", Map.of("type", "array",
                        "description", "Value records. Each: {value (required), "
                                + "mode: recurring|one_time, period: {count, unit: "
                                + "day|week|month|year}, validFrom, validTo (ISO), sign, "
                                + "interest: {rate, period, basis, compound}}. "
                                + "recurring needs period; one_time needs validFrom."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "name", "values"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final FinanceService financeService;

    public FinanceNodeValueSetTool(EddieContext eddieContext, DocumentService documentService,
                                   FinanceService financeService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.financeService = financeService;
    }

    @Override public String name() { return "finance_node_value_set"; }

    @Override
    public String description() {
        return "Replace a finance-tree node's value records. A recurring value is a rate "
                + "(value per period, e.g. 800/month); a one_time value is a lump at a date "
                + "(validFrom). Interest is tracked separately as {rate, period}. This "
                + "replaces ALL of the node's values.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "finance"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        FinanceToolSupport.Resolved r =
                FinanceToolSupport.resolveByPath(eddieContext, documentService, params, ctx);
        String nodeName = FinanceToolSupport.paramString(params, "name");
        if (nodeName == null) throw new ToolException("`name` is required");

        List<FinanceValue> values = new ArrayList<>();
        for (Map<String, Object> raw : FinanceToolSupport.paramMapList(params, "values")) {
            values.add(FinanceTreeCodec.valueFromMap(raw));
        }

        financeService.setValues(r.doc(), nodeName, values, ctx.userId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", r.doc().getPath());
        result.put("name", nodeName);
        result.put("valueCount", values.size());
        return result;
    }
}
