package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.shared.document.DocumentNote;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Add a sticky-note to an existing document. Returns the generated
 * {@code noteId} the LLM can later pass to {@code doc_note_update}
 * or {@code doc_note_delete}.
 *
 * <p>Notes do not modify the document body — they are a separate
 * sticky-note layer, atomic against the per-document cap ({@link
 * DocumentService#NOTES_MAX}). Exceeding the cap throws a
 * {@link ToolException} with a recovery hint.
 */
@Component
@RequiredArgsConstructor
public class DocNoteAddTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("text"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("text", Map.of("type", "string",
                "description", "Free-form note text. Markdown is allowed; the UI will render it."));
        p.put("line", Map.of("type", "integer",
                "description", "Optional 1-based line number the note anchors to. Omit for a "
                        + "file-level (unanchored) note. The line reference is static — it does "
                        + "not follow content edits."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_note_add"; }
    @Override public String description() {
        return "Attach a sticky-note to a document. Notes are separate from the body and survive "
                + "edits. Returns the noteId you need for later doc_note_update / doc_note_delete.";
    }
    @Override public boolean primary() { return true; }
    @Override public Set<String> labels() { return Set.of("text-edit", "write", "document", "note"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.loadDocumentForWrite(params, ctx, de.mhus.vance.shared.permission.Action.WRITE);
        String text = KindToolSupport.requireRawString(params, "text");
        Integer line = KindToolSupport.paramInt(params, "line");
        String userId = ctx.userId() == null || ctx.userId().isBlank() ? "agent" : ctx.userId();

        DocumentNote note;
        try {
            note = support.documentService().addNote(doc.getId(), text, userId, line);
        } catch (DocumentService.NotesLimitExceededException e) {
            throw new ToolException("Document already has the maximum number of notes ("
                    + DocumentService.NOTES_MAX
                    + "). Delete or merge older notes before adding new ones.");
        }
        support.emitNotesInvalidate(doc, ctx);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        out.put("noteId", note.getId());
        out.put("createdAt", note.getCreatedAt().toString());
        return out;
    }
}
