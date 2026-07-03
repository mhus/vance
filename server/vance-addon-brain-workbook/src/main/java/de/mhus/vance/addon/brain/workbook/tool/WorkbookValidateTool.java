package de.mhus.vance.addon.brain.workbook.tool;

import de.mhus.vance.addon.brain.workbook.validate.WorkbookValidationService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Read-only static check of a workbook folder or a single workpage: verifies
 * every {@code vance-*} fence (form / input / button / embed) has its required
 * keys, that referenced documents / scripts exist and have the right kind /
 * {@code .js} extension, and that a records data doc carries no legacy
 * {@code $meta.form}/{@code onSave}. Use it after building or editing a
 * workbook to self-check before telling the user it's done.
 */
@Component
@Slf4j
public class WorkbookValidateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string",
                        "description", "A workbook folder (e.g. 'apps/grades') or a "
                                + "single workpage document path (e.g. "
                                + "'apps/grades/rechner.workpage.md')."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path"));

    private final EddieContext eddieContext;
    private final WorkbookValidationService validationService;

    public WorkbookValidateTool(EddieContext eddieContext,
                                WorkbookValidationService validationService) {
        this.eddieContext = eddieContext;
        this.validationService = validationService;
    }

    @Override public String name() { return "workbook_validate"; }

    @Override
    public String description() {
        return "Statically validate a workbook folder or a single workpage: "
                + "checks vance-form/input/button/embed fences for required keys, "
                + "resolvable references (config/uri/script/saveScript exist + right "
                + "kind + .js), field types, and legacy $meta on the data doc. "
                + "Read-only. Returns { ok, errors, warnings, findings[] }. Does NOT "
                + "check runtime script logic. Run it after building/editing.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("read-only", "workbook", "document");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("path");
        if (!(raw instanceof String path) || path.isBlank()) {
            throw new ToolException("'path' is required");
        }
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        WorkbookValidationService.Result result =
                validationService.validate(ctx.tenantId(), project.getName(), path.trim());
        log.info("WorkbookValidateTool path='{}' ok={} errors+warnings={}",
                path, result.ok(), result.findings().size());
        return result.toMap();
    }
}
