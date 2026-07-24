package de.mhus.vance.addon.brain.finance.tool;

import de.mhus.vance.addon.brain.finance.FinanceService;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.report.FinanceReport;
import de.mhus.vance.addon.brain.finance.report.FinanceReportProcessor;
import de.mhus.vance.addon.brain.finance.report.FinanceReportRegistry;
import de.mhus.vance.addon.brain.finance.report.ReportContext;
import de.mhus.vance.addon.brain.finance.report.ReportParams;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Generate a report from a finance-tree via a named processor. */
@Component
public class FinanceReportGenerateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string", "description", "Source finance-tree path."));
                put("processor", Map.of("type", "string",
                        "description", "Processor type (see finance_report_processors), "
                                + "e.g. table, series, assessment."));
                put("params", Map.of("type", "object",
                        "description", "Processor params, e.g. {from, to, granularity, chartType}."));
                put("persist", Map.of("type", "boolean",
                        "description", "true to save the report as a document at outputPath."));
                put("outputPath", Map.of("type", "string",
                        "description", "Target path when persist=true."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "processor"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final FinanceService financeService;
    private final FinanceReportRegistry registry;

    public FinanceReportGenerateTool(EddieContext eddieContext, DocumentService documentService,
                                     FinanceService financeService, FinanceReportRegistry registry) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.financeService = financeService;
        this.registry = registry;
    }

    @Override public String name() { return "finance_report_generate"; }

    @Override
    public String description() {
        return "Generate a report from a finance-tree using a processor (table→sheet, "
                + "series→chart, assessment→markdown, …). With persist=true the report is "
                + "saved at outputPath; otherwise its body is returned inline. List "
                + "processors with finance_report_processors.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "finance"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        FinanceToolSupport.Resolved r =
                FinanceToolSupport.resolveByPath(eddieContext, documentService, params, ctx);
        String type = FinanceToolSupport.paramString(params, "processor");
        if (type == null) throw new ToolException("`processor` is required");
        FinanceReportProcessor processor = registry.find(type);
        if (processor == null) throw new ToolException("Unknown report processor '" + type + "'.");

        FinanceTreeDocument tree = financeService.readDocument(r.doc());
        ReportContext reportCtx = new ReportContext(
                r.tenantId(), r.projectName(), ctx.processId(), ctx.userId());
        FinanceReport report = processor.render(
                tree, ReportParams.of(FinanceToolSupport.paramMap(params, "params")), reportCtx);

        boolean persist = params.get("persist") instanceof Boolean b && b;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processor", type);
        result.put("outputKind", report.outputKind());
        if (persist) {
            String outputPath = FinanceToolSupport.paramString(params, "outputPath");
            if (outputPath == null) throw new ToolException("outputPath is required when persist=true");
            DocumentDocument stored = financeService.createReport(
                    r.tenantId(), r.projectName(), outputPath, report, ctx.userId());
            result.put("path", stored.getPath());
            result.put("id", stored.getId());
        } else {
            result.put("mimeType", report.mimeType());
            result.put("body", report.body());
        }
        return result;
    }
}
