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
 * Restore a saved version. Two modes:
 * <ul>
 *   <li><b>overwrite</b> (default) — write the chosen version back onto the
 *       live document. The current content is archived first, so the restore
 *       is itself undoable.</li>
 *   <li><b>copy</b> ({@code newFile:true} or a {@code targetPath}) — create a
 *       NEW document beside the live one carrying the version's content; the
 *       live document is left untouched. Without {@code targetPath} the name is
 *       auto-generated ({@code foo.yaml} → {@code foo-version-<N>-<date>.yaml})
 *       so nothing is overwritten.</li>
 * </ul>
 *
 * <p>Distinct from {@code doc_restore}, which pulls a document out of the
 * trash. Pick the {@code archiveId} from {@code doc_version_list}. WRITE
 * (overwrite) / READ-source + CREATE-target (copy) are enforced at the source
 * and the {@code DocumentService} chokepoint; a lineage mismatch is rejected.
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
        p.put("newFile", Map.of("type", "boolean",
                "description", "When true, restore into a NEW file beside the current one "
                        + "instead of overwriting it. Implied when targetPath is set. "
                        + "Default false (overwrite the live document)."));
        p.put("targetPath", Map.of("type", "string",
                "description", "Optional path for the new file (implies newFile). "
                        + "Omit to auto-generate foo-version-<N>-<date>.<ext>."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_version_restore"; }

    @Override
    public String description() {
        return "Restore a saved version. Default: overwrite the live document "
                + "(current content archived first, undoable). With newFile=true "
                + "or a targetPath: restore into a NEW file beside it instead "
                + "(name auto-generated, nothing overwritten). Get the archiveId "
                + "from doc_version_list. Not for trash — that is doc_restore. "
                + "Select the document by path or id.";
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

        String targetPath = KindToolSupport.paramString(params, "targetPath");
        Boolean newFile = KindToolSupport.paramBoolean(params, "newFile");
        boolean asCopy = targetPath != null || Boolean.TRUE.equals(newFile);

        DocumentDocument result;
        try {
            result = asCopy
                    ? support.documentService().restoreArchiveToNewDocument(
                            doc.getId(), archiveId, targetPath, support.writeActor(ctx, doc))
                    : support.documentService().restoreArchive(
                            doc.getId(), archiveId, support.writeActor(ctx, doc));
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        } catch (de.mhus.vance.shared.document.DocumentService.DocumentAlreadyExistsException e) {
            throw new ToolException(e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", asCopy ? "copy" : "overwrite");
        out.put("documentId", result.getId());
        out.put("path", result.getPath());
        out.put("restoredFromArchiveId", archiveId);
        out.put("size", result.getSize());
        return out;
    }
}
