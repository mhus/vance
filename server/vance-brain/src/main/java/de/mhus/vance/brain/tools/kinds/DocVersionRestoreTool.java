package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Restore a saved version onto the live document. The current content is
 * archived first (so the restore is itself undoable), then the chosen
 * version's body is written back. Distinct from {@code doc_restore}, which
 * pulls a document out of the trash — this reverts content to an earlier
 * version of the same live document.
 *
 * <p>Pick the {@code archiveId} from {@code doc_version_list}. WRITE is
 * enforced at the resolution source and again at the {@code DocumentService}
 * chokepoint; a lineage mismatch (archive belongs to another document) is
 * rejected.
 *
 * <p>Sibling tools: {@code doc_version_snapshot}, {@code doc_version_list}.
 * See {@code specification/public/document-versioning.md} §3.2/§11.
 */
@Component
@RequiredArgsConstructor
public class DocVersionRestoreTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("archiveId"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("archiveId", Map.of("type", "string",
                "description", "Version to restore — an archiveId from doc_version_list."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_version_restore"; }

    @Override
    public String description() {
        return "Restore a saved version onto the live document. The current "
                + "content is archived first (undoable), then the chosen version "
                + "is written back. Get the archiveId from doc_version_list. Not "
                + "for trash — that is doc_restore. Select the document by path "
                + "or id.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("doc-management", "eddie", "write", "document");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.loadDocumentForWrite(
                params, ctx, de.mhus.vance.shared.permission.Action.WRITE);
        String archiveId = KindToolSupport.paramString(params, "archiveId");
        if (archiveId == null) throw new ToolException("archiveId is required");

        DocumentDocument restored;
        try {
            restored = support.documentService().restoreArchive(
                    doc.getId(), archiveId, support.writeActor(ctx, doc));
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", restored.getId());
        out.put("path", restored.getPath());
        out.put("restoredFromArchiveId", archiveId);
        out.put("size", restored.getSize());
        return out;
    }
}
