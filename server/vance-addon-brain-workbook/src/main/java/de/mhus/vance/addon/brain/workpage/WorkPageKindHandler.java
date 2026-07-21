package de.mhus.vance.addon.brain.workpage;

import de.mhus.vance.addon.brain.workbook.validate.WorkbookValidationService;
import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Registers the {@code workpage} document kind and wires its semantic
 * validation into the generic {@code KindValidationService}. Validation
 * delegates to {@link WorkbookValidationService} — the canonical workpage
 * parser + {@code BlockValidator} dispatch — so there is one workpage
 * validator, not two: {@code workbook_validate} and {@code kind_validate}
 * both flow through it.
 */
@Service
public class WorkPageKindHandler implements KindHandler {

    private final WorkbookValidationService validationService;

    public WorkPageKindHandler(WorkbookValidationService validationService) {
        this.validationService = validationService;
    }

    @Override
    public String getName() {
        return WorkPageService.KIND;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        return validationService.validate(content, ctx.docPath(), ctx.docs()).findings();
    }
}
