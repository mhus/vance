package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.addon.brain.workbook.validate.Finding;
import de.mhus.vance.addon.brain.workbook.validate.WorkbookValidationService;
import java.util.List;

/**
 * Response for {@code GET /brain/{tenant}/addon/workbook/validate}: the
 * validation findings for a workbook folder or workpage, for the future
 * "Validate" panel in the Web-UI. Mirrors the {@code workbook_validate} tool
 * output.
 */
public record WorkbookValidationResponse(
        String target,
        boolean ok,
        long errors,
        long warnings,
        int pagesChecked,
        int blocksChecked,
        List<Finding> findings) {

    public static WorkbookValidationResponse from(WorkbookValidationService.Result r) {
        long errors = r.findings().stream()
                .filter(f -> f.level() == Finding.Level.ERROR).count();
        return new WorkbookValidationResponse(
                r.target(), r.ok(), errors, r.findings().size() - errors,
                r.pagesChecked(), r.blocksChecked(), r.findings());
    }
}
