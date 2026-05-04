package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Soft-delete a document — moves it into the project's trash folder
 * ({@code _vance/bin/<UUID>_<name>}). The document keeps its id and
 * body; only the path changes. Use {@code doc_restore} to bring it
 * back, {@code doc_purge} to delete permanently.
 */
@Component
@RequiredArgsConstructor
public class DocDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "doc_delete"; }
    @Override public String description() {
        return "Soft-delete a document by moving it to the project's trash folder "
                + "(`_vance/bin/<UUID>_<name>`). The id stays valid for `doc_restore`. "
                + "For permanent removal use `doc_purge`.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("doc-management", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.loadDocument(params, ctx);
        // Flush pending writes so the trashed copy reflects the
        // latest in-flight body, not stale disk content.
        support.buffer().flush(ctx.processId(), doc.getId());
        DocumentDocument trashed = support.documentService().trash(doc.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", trashed.getId());
        out.put("originalPath", doc.getPath());
        out.put("trashPath", trashed.getPath());
        return out;
    }
}
