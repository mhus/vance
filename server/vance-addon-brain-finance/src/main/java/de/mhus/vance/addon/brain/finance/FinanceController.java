package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.addon.brain.finance.model.FinanceComputed;
import de.mhus.vance.addon.brain.finance.model.FinanceProjection;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.report.FinanceReport;
import de.mhus.vance.addon.brain.finance.report.FinanceReportProcessor;
import de.mhus.vance.addon.brain.finance.report.FinanceReportRegistry;
import de.mhus.vance.addon.brain.finance.report.ReportContext;
import de.mhus.vance.addon.brain.finance.report.ReportParams;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the finance addon under {@code /brain/{tenant}/addon/finance/...}.
 * Convenience for the Web-UI; the LLM path is the {@code finance_*} tools. Every
 * endpoint delegates to {@link FinanceService} / {@link FinanceReportRegistry}.
 */
@RestController
@RequiredArgsConstructor
public class FinanceController {

    private final FinanceService financeService;
    private final FinanceReportRegistry registry;
    private final DocumentService documentService;
    private final RequestAuthority authority;

    public record ProcessorInfo(String type, String title, String outputKind,
                                @Nullable String paramForm) {}

    public record ReportResult(String outputKind, String mimeType,
                               @Nullable String body, @Nullable String path,
                               @Nullable String id) {}

    @GetMapping("/brain/{tenant}/addon/finance/processors")
    public List<ProcessorInfo> processors(@PathVariable String tenant,
                                          @RequestParam String projectId,
                                          HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        List<ProcessorInfo> out = new ArrayList<>();
        for (FinanceReportProcessor p : registry.list()) {
            out.add(new ProcessorInfo(p.type(), p.title(), p.outputKind(), p.paramForm()));
        }
        return out;
    }

    @GetMapping("/brain/{tenant}/addon/finance/tree")
    public FinanceTreeDto getTree(@PathVariable String tenant,
                                  @RequestParam String projectId,
                                  @RequestParam String path,
                                  HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return FinanceDtoMapper.toDto(
                financeService.readDocument(requireDoc(tenant, projectId, path)));
    }

    @PutMapping("/brain/{tenant}/addon/finance/tree")
    public FinanceTreeDto putTree(@PathVariable String tenant,
                                  @RequestParam String projectId,
                                  @RequestParam String path,
                                  @RequestBody FinanceTreeDto dto,
                                  HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        DocumentDocument doc = requireDoc(tenant, projectId, path);
        financeService.writeDocument(doc, FinanceDtoMapper.fromDto(dto), null, currentUser(request));
        return FinanceDtoMapper.toDto(financeService.readDocument(doc));
    }

    @PostMapping("/brain/{tenant}/addon/finance/create")
    public FinanceTreeDto create(@PathVariable String tenant,
                                 @RequestParam String projectId,
                                 @RequestParam String path,
                                 @RequestParam(required = false) @Nullable String title,
                                 HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.CREATE);
        DocumentDocument stored =
                financeService.create(tenant, projectId, path, title, null, currentUser(request));
        return FinanceDtoMapper.toDto(financeService.readDocument(stored));
    }

    @PostMapping("/brain/{tenant}/addon/finance/calc")
    public FinanceComputed calc(@PathVariable String tenant,
                                @RequestParam String projectId,
                                @RequestParam String path,
                                HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        return financeService.recalculate(requireDoc(tenant, projectId, path), currentUser(request));
    }

    @GetMapping("/brain/{tenant}/addon/finance/snapshot")
    public FinanceComputed snapshot(@PathVariable String tenant,
                                    @RequestParam String projectId,
                                    @RequestParam String path,
                                    HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return financeService.snapshot(requireDoc(tenant, projectId, path));
    }

    @GetMapping("/brain/{tenant}/addon/finance/project")
    public FinanceProjection project(@PathVariable String tenant,
                                     @RequestParam String projectId,
                                     @RequestParam String path,
                                     @RequestParam String from,
                                     @RequestParam String to,
                                     @RequestParam(defaultValue = "month") String granularity,
                                     HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        PeriodUnit g = PeriodUnit.parse(granularity);
        if (g == null) throw new ToolException("Unknown granularity '" + granularity + "'.");
        return financeService.project(requireDoc(tenant, projectId, path),
                parseDate(from, "from"), parseDate(to, "to"), g);
    }

    @PostMapping("/brain/{tenant}/addon/finance/report")
    public ReportResult report(@PathVariable String tenant,
                               @RequestParam String projectId,
                               @RequestParam String path,
                               @RequestParam String processor,
                               @RequestParam(defaultValue = "false") boolean persist,
                               @RequestParam(required = false) @Nullable String outputPath,
                               @RequestBody(required = false) @Nullable Map<String, Object> params,
                               HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId),
                persist ? Action.CREATE : Action.READ);
        FinanceReportProcessor proc = registry.find(processor);
        if (proc == null) throw new ToolException("Unknown report processor '" + processor + "'.");

        FinanceReport rep = proc.render(
                financeService.readDocument(requireDoc(tenant, projectId, path)),
                ReportParams.of(params == null ? Map.of() : params),
                new ReportContext(tenant, projectId, null, currentUser(request)));

        if (persist) {
            if (outputPath == null || outputPath.isBlank()) {
                throw new ToolException("outputPath is required when persist=true");
            }
            DocumentDocument stored = financeService.createReport(
                    tenant, projectId, outputPath, rep, currentUser(request));
            return new ReportResult(rep.outputKind(), rep.mimeType(), null,
                    stored.getPath(), stored.getId());
        }
        return new ReportResult(rep.outputKind(), rep.mimeType(), rep.body(), null, null);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private DocumentDocument requireDoc(String tenant, String projectId, String path) {
        return documentService.findByPath(tenant, projectId, path)
                .orElseThrow(() -> new ToolException("No finance-tree at '" + path + "'."));
    }

    private static LocalDate parseDate(String iso, String field) {
        try {
            return LocalDate.parse(iso.trim());
        } catch (DateTimeParseException e) {
            throw new ToolException("Invalid '" + field + "' date '" + iso + "' (expect yyyy-MM-dd).");
        }
    }

    private static @Nullable String currentUser(HttpServletRequest request) {
        Object v = request.getAttribute("vanceUserId");
        return v instanceof String s ? s : null;
    }
}
