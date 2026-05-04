package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Rename / move a document to a new path inside the same project.
 * The id stays stable, only {@code path} and {@code name} change.
 *
 * <p>Cross-project moves are a separate {@code cross_doc_move} tool.
 */
@Component
@RequiredArgsConstructor
public class DocMoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("newPath"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("newPath", Map.of("type", "string",
                "description", "New path within the same project. Must not already exist."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_move"; }
    @Override public String description() {
        return "Rename or move a document to a new path within the same project. The document id "
                + "stays the same; only `path` and `name` change. Pending buffered writes are "
                + "flushed first so the rename doesn't lose in-flight content.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("doc-management", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.loadDocument(params, ctx);
        String newPath = KindToolSupport.requireString(params, "newPath");
        // Flush any pending writes — DocumentService.update() reads
        // the current MongoDB row, not the in-buffer body.
        support.buffer().flush(ctx.processId(), doc.getId());
        DocumentDocument moved;
        try {
            moved = support.documentService().update(doc.getId(), null, null, null, newPath);
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ToolException(e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", moved.getId());
        out.put("previousPath", doc.getPath());
        out.put("newPath", moved.getPath());
        return out;
    }
}
