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
 * Restore a trashed document from {@code _vance/bin/} back to its
 * original path (or to a path the caller specifies). Errors when the
 * target path is occupied — caller picks a different one.
 */
@Component
@RequiredArgsConstructor
public class DocRestoreTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("newPath", Map.of("type", "string",
                "description", "Optional target path. Default: the original path remembered "
                        + "when the document was trashed (header `_trash-original-path`)."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_restore"; }
    @Override public String description() {
        return "Restore a trashed document from `_vance/bin/`. By default it goes back to its "
                + "original path; pass `newPath` to land it somewhere else. Errors when the "
                + "target is already occupied.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("doc-management", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.loadDocument(params, ctx);
        String newPath = KindToolSupport.paramString(params, "newPath");
        DocumentDocument restored;
        try {
            restored = support.documentService().restore(doc.getId(), newPath);
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ToolException(e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", restored.getId());
        out.put("trashPath", doc.getPath());
        out.put("restoredPath", restored.getPath());
        return out;
    }
}
