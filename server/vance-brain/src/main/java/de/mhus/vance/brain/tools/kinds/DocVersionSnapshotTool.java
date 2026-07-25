package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
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
 * Manually snapshot a document as a new version — the LLM-facing counterpart
 * of the web UI's "create version now" button. Bypasses the auto-archive
 * min-interval cooldown (an explicit request, not an autosave burst) but keeps
 * the content-diff guard: if the current content is byte-identical to the
 * latest archived version, no duplicate version is created.
 *
 * <p>WRITE is enforced at the resolution source via
 * {@link KindToolSupport#loadDocumentForWrite} and again at the
 * {@link DocumentService} chokepoint. A caller with only READ on the document
 * gets a permission error, no snapshot.
 *
 * <p>Sibling tools: {@code doc_version_list}, {@code doc_version_restore}.
 * See {@code specification/public/document-versioning.md} §4/§11.
 */
@Component
@RequiredArgsConstructor
public class DocVersionSnapshotTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "doc_version_snapshot"; }

    @Override
    public String description() {
        return "Save the current state of a document as a new version (snapshot). "
                + "Use before a risky rewrite or at a milestone so it can be "
                + "restored later with doc_version_restore. Bypasses the version "
                + "cooldown but skips the write when the content is unchanged "
                + "since the last version (no duplicate versions) — returns "
                + "created=false with reason=UNCHANGED then. Select the document "
                + "by path or id.";
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
        DocumentService.CreateVersionResult result;
        try {
            result = support.documentService().createVersionNow(
                    doc.getId(), support.writeActor(ctx, doc));
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        out.put("created", result.created());
        out.put("reason", result.reason().name());
        if (result.archive() != null) {
            out.put("archiveId", result.archive().getId());
            if (result.archive().getArchivedAt() != null) {
                out.put("archivedAtMs", result.archive().getArchivedAt().toEpochMilli());
            }
        }
        return out;
    }
}
