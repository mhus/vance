package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.shared.document.DocumentArchiveDocument;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * List the archived versions of a document, newest first — so the model can
 * pick an {@code archiveId} to hand to {@code doc_version_restore}. READ on the
 * document is enforced at the resolution source.
 *
 * <p>Sibling tools: {@code doc_version_snapshot}, {@code doc_version_restore}.
 * See {@code specification/public/document-versioning.md} §4/§11.
 */
@Component
@RequiredArgsConstructor
public class DocVersionListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "doc_version_list"; }

    @Override
    public String description() {
        return "List the saved versions of a document, newest first. Each entry "
                + "has an archiveId (pass to doc_version_restore), a timestamp "
                + "(archivedAtMs) and size. Select the document by path or id.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("doc-management", "eddie", "read", "document");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        // loadDocument enforces READ at the resolution source.
        DocumentDocument doc = support.loadDocument(params, ctx);
        List<DocumentArchiveDocument> archives = support.documentService().listArchives(doc);

        List<Map<String, Object>> versions = new ArrayList<>(archives.size());
        for (DocumentArchiveDocument a : archives) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("archiveId", a.getId());
            v.put("archivedAtMs", a.getArchivedAt() == null ? 0L : a.getArchivedAt().toEpochMilli());
            v.put("size", a.getSize());
            v.put("path", a.getPath());
            versions.add(v);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        out.put("count", versions.size());
        out.put("versions", versions);
        return out;
    }
}
