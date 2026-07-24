package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Permanently delete a document — Mongo row gone, storage blob
 * scheduled for cleanup. There is no undo. To accidentally-protect,
 * the tool requires the document to already be in the trash folder
 * ({@code _vance/trash/}) — a two-step delete: first {@code doc_delete}
 * (soft), then {@code doc_purge} (hard). Set
 * {@code force: true} to skip the trash requirement when you really
 * mean it.
 */
@Component
@RequiredArgsConstructor
public class DocPurgeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("force", Map.of("type", "boolean",
                "description", "Permit purging documents that are not in the trash folder. "
                        + "Default: false (two-step delete required)."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_purge"; }
    @Override public String description() {
        return "Permanently delete a document. By default the document must already be in the "
                + "trash (use `doc_delete` first); set `force: true` to skip that check. "
                + "There is no undo.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("doc-management", "eddie", "write", "document"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        // Load READ-enforced only; the DELETE authorization branches below by
        // whether this is a trash purge or a forced live-doc purge.
        DocumentDocument doc = support.loadDocument(params, ctx);
        boolean force = Boolean.TRUE.equals(KindToolSupport.paramBoolean(params, "force"));
        boolean trash = DocumentService.isTrash(doc.getPath());
        if (!force && !trash) {
            throw new ToolException("doc_purge requires the document to be in the trash folder "
                    + "(use doc_delete first), or set force=true. Path was: '" + doc.getPath() + "'");
        }

        if (trash) {
            // Trash purge: a project admin may empty any trash entry, everyone
            // else only their own (permission-system variant B — the admin
            // verdict is asked of the pluggable provider, not a role test).
            // Under a fully write-protected _vance/ the trash path itself is
            // reserved, so the delete runs as a trusted server write once this
            // gate has approved it.
            if (!support.canPurgeTrash(ctx, doc)) {
                throw new ToolException("doc_purge: only the document's creator or a project admin "
                        + "may purge this trash entry (path='" + doc.getPath() + "')");
            }
            support.buffer().flushAll(ctx.processId());
            support.documentService().delete(doc.getId(), support.systemWriteActor(ctx));
        } else {
            // Forced purge of a live document: the ordinary DELETE role check
            // applies at the document's own path.
            support.enforceDocWrite(ctx, doc, de.mhus.vance.shared.permission.Action.DELETE);
            support.buffer().flushAll(ctx.processId());
            support.documentService().delete(doc.getId(), support.writeActor(ctx, doc));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purgedId", doc.getId());
        out.put("purgedPath", doc.getPath());
        out.put("forced", force);
        return out;
    }
}
