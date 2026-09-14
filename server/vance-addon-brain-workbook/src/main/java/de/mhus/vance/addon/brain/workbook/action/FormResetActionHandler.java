package de.mhus.vance.addon.brain.workbook.action;

import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.addon.brain.workpage.WorkPageDocument;
import de.mhus.vance.addon.brain.workpage.WorkPageService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code vance-button} {@code type: form-reset} — removes the
 * {@code verdict}/{@code feedback} markings written by {@code form-resolve}
 * (or an LLM grading turn) from every {@code vance-field} on the page. The
 * answers ({@code value}) are deliberately kept — reset clears the
 * markings, not the work.
 */
@Component
public class FormResetActionHandler implements ButtonActionHandler {

    private final DocumentService documentService;
    private final WorkPageService workPageService;

    public FormResetActionHandler(DocumentService documentService, WorkPageService workPageService) {
        this.documentService = documentService;
        this.workPageService = workPageService;
    }

    @Override
    public String type() {
        return "form-reset";
    }

    @Override
    public ButtonActionResult run(ButtonActionContext ctx) {
        DocumentDocument doc = findWorkPage(ctx);
        WorkPageDocument page = workPageService.readDocument(doc);
        List<Block> blocks = new ArrayList<>(page.blocks());
        boolean changed = FieldWalk.walk(blocks, field -> {
            if (field.verdict() == null && field.feedback() == null) return null;
            return new Block.Field(
                    field.id(),
                    field.fieldType(),
                    field.question(),
                    field.options(),
                    field.solution(),
                    field.value(),
                    null,
                    null);
        });
        if (changed) {
            workPageService.writeDocument(doc, page.withBlocks(blocks));
            return new ButtonActionResult("Markings cleared — answers kept.");
        }
        return new ButtonActionResult("No markings to clear.");
    }

    private DocumentDocument findWorkPage(ButtonActionContext ctx) {
        DocumentDocument doc = documentService
                .findByPath(ctx.tenantId(), ctx.projectId(), ctx.pagePath())
                .orElseThrow(() -> new ToolException("workpage not found: " + ctx.pagePath()));
        if (!WorkPageService.KIND.equals(doc.getKind())) {
            throw new ToolException("'" + ctx.pagePath() + "' is not a workpage (kind=" + doc.getKind() + ").");
        }
        return doc;
    }
}
