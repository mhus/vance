package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Remove a sticky-note from a document. Idempotent — deleting a
 * non-existent note is a silent no-op (the {@code deleted} flag in
 * the result tells the LLM what actually happened).
 */
@Component
@RequiredArgsConstructor
public class DocNoteDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("noteId"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("noteId", Map.of("type", "string",
                "description", "Id of the note to remove — get it from doc_note_list."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_note_delete"; }
    @Override public String description() {
        return "Remove a sticky-note from a document by id. Idempotent: deleting an unknown noteId "
                + "returns deleted=false without erroring.";
    }
    @Override public boolean primary() { return true; }
    @Override public Set<String> labels() { return Set.of("text-edit", "write", "document", "note"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.loadDocumentForWrite(params, ctx, de.mhus.vance.shared.permission.Action.WRITE);
        String noteId = KindToolSupport.requireString(params, "noteId");

        boolean removed = support.documentService().deleteNote(doc.getId(), noteId);
        if (removed) {
            support.emitNotesInvalidate(doc, ctx);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        out.put("noteId", noteId);
        out.put("deleted", removed);
        return out;
    }
}
