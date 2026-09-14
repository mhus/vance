package de.mhus.vance.addon.brain.workbook.action;

import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.addon.brain.workpage.FieldValues;
import de.mhus.vance.addon.brain.workpage.WorkPageDocument;
import de.mhus.vance.addon.brain.workpage.WorkPageService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code vance-button} {@code type: form-resolve} — grades every
 * {@code vance-field} on the page that carries a {@code solution},
 * mechanically (no LLM): closed types are compared by option index, text
 * types are not graded in v1 and stay untouched. Writes {@code verdict}
 * (and drops a stale {@code feedback} when the verdict changes) directly
 * into the page markdown; the open editor picks the change up via the
 * documents channel. Fields without a {@code solution} are skipped — they
 * are plain form elements.
 */
@Component
public class FormResolveActionHandler implements ButtonActionHandler {

    private final DocumentService documentService;
    private final WorkPageService workPageService;

    public FormResolveActionHandler(DocumentService documentService, WorkPageService workPageService) {
        this.documentService = documentService;
        this.workPageService = workPageService;
    }

    @Override
    public String type() {
        return "form-resolve";
    }

    @Override
    public ButtonActionResult run(ButtonActionContext ctx) {
        DocumentDocument doc = findWorkPage(ctx);
        WorkPageDocument page = workPageService.readDocument(doc);
        List<Block> blocks = new ArrayList<>(page.blocks());
        int[] checkable = {0};
        int[] correct = {0};
        boolean changed = FieldWalk.walk(blocks, field -> {
            String verdict = resolveVerdict(field);
            if (verdict == null) return null; // no solution, or not mechanically graded
            checkable[0]++;
            if ("correct".equals(verdict)) correct[0]++;
            if (verdict.equals(field.verdict())) return null;
            // feedback belonged to the previous verdict — stale now.
            return new Block.Field(
                    field.id(),
                    field.fieldType(),
                    field.question(),
                    field.options(),
                    field.solution(),
                    field.value(),
                    verdict,
                    null);
        });
        if (checkable[0] == 0) {
            return new ButtonActionResult("Nothing to check — add a `solution` to the fields you want graded.");
        }
        if (changed) {
            workPageService.writeDocument(doc, page.withBlocks(blocks));
        }
        return new ButtonActionResult(correct[0] + " of " + checkable[0] + " answers correct");
    }

    /**
     * Mechanical verdict for one field — {@code null} when the field is not
     * checkable (no {@code solution}) or not mechanically graded
     * ({@code text}/{@code textarea} in v1). A blank answer counts as
     * {@code wrong}.
     */
    private static String resolveVerdict(Block.Field field) {
        if (field.solution() == null) return null;
        return switch (field.fieldType()) {
            case "choice", "dropdown" ->
                Objects.equals(FieldValues.asIndex(field.value()), FieldValues.asIndex(field.solution()))
                        ? "correct"
                        : "wrong";
            case "multi" ->
                Objects.equals(FieldValues.asIndices(field.value()), FieldValues.asIndices(field.solution()))
                        ? "correct"
                        : "wrong";
            default -> null;
        };
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
