package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.shared.document.DocumentNote;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Patch fields on an existing sticky-note. Any combination of
 * {@code text} / {@code done} / {@code line} may be passed —
 * omitted fields stay untouched. At least one field must be
 * provided; no-op updates are flagged as bugs.
 *
 * <p>Pass {@code line = 0} to <em>clear</em> the line anchor (the
 * underlying service uses {@code Integer.MIN_VALUE} as the sentinel
 * for "unset"; we translate {@code 0} on the tool boundary because
 * lines are 1-based — {@code 0} cannot otherwise be a valid value).
 */
@Component
@RequiredArgsConstructor
public class DocNoteUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("noteId"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("noteId", Map.of("type", "string",
                "description", "Id of the note to update — get it from doc_note_list."));
        p.put("text", Map.of("type", "string",
                "description", "New note text. Omit to leave unchanged."));
        p.put("done", Map.of("type", "boolean",
                "description", "Mark the note done (true) or open (false). Omit to leave unchanged."));
        p.put("line", Map.of("type", "integer",
                "description", "New 1-based line anchor. Pass 0 to clear the anchor entirely. "
                        + "Omit to leave unchanged."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_note_update"; }
    @Override public String description() {
        return "Patch fields on an existing sticky-note: text, done flag, or line anchor. At least "
                + "one field must be provided. Use line=0 to clear the line anchor.";
    }
    @Override public boolean primary() { return true; }
    @Override public Set<String> labels() { return Set.of("text-edit", "write", "document", "note"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.loadDocumentForWrite(params, ctx, de.mhus.vance.shared.permission.Action.WRITE);
        String noteId = KindToolSupport.requireString(params, "noteId");
        String newText = KindToolSupport.paramRawString(params, "text");
        Boolean newDone = KindToolSupport.paramBoolean(params, "done");
        Integer rawLine = KindToolSupport.paramInt(params, "line");

        if (newText == null && newDone == null && rawLine == null) {
            throw new ToolException(
                    "doc_note_update needs at least one of text/done/line — nothing to patch");
        }

        // Translate 0 → MIN_VALUE (the service's sentinel for "clear the anchor").
        Integer newLine = rawLine;
        if (rawLine != null && rawLine == 0) {
            newLine = Integer.MIN_VALUE;
        }

        Optional<DocumentNote> result = support.documentService()
                .updateNote(doc.getId(), noteId, newText, newDone, newLine);
        if (result.isEmpty()) {
            throw new ToolException("Note id='" + noteId + "' not found on document "
                    + support.identify(doc));
        }
        support.emitNotesInvalidate(doc, ctx);

        DocumentNote note = result.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        out.put("noteId", note.getId());
        out.put("updatedAt", note.getUpdatedAt().toString());
        return out;
    }
}
