package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentNote;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * List the sticky-notes attached to a document. Note-edit / delete
 * operations need the {@code noteId} surfaced by this tool —
 * {@code doc_read} and {@code doc_info} intentionally do not include
 * notes in their output to keep their body-focused responses small.
 */
@Component
@RequiredArgsConstructor
public class DocNoteListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "doc_note_list"; }
    @Override public String description() {
        return "List all sticky-notes attached to a document, ordered by display order "
                + "(then by creation time). Each entry carries the noteId you need for "
                + "doc_note_update / doc_note_delete.";
    }
    @Override public boolean primary() { return true; }
    @Override public boolean contributesPrak() {
        // Listing — note titles only, no synthesised insight.
        return false;
    }
    @Override public Set<String> labels() { return Set.of("text-edit", "read", "document", "note"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.loadDocument(params, ctx);
        Map<String, DocumentNote> notes = doc.getNotes();
        List<DocumentNote> sorted = notes == null ? List.of() : new ArrayList<>(notes.values());
        sorted = new ArrayList<>(sorted);
        sorted.sort(Comparator
                .comparing((DocumentNote n) -> n.getOrder() == null
                        ? (n.getCreatedAt() == null ? 0d
                                : (double) n.getCreatedAt().toEpochMilli())
                        : n.getOrder())
                .thenComparing(n -> n.getCreatedAt() == null
                        ? java.time.Instant.EPOCH
                        : n.getCreatedAt()));

        List<Map<String, Object>> out = new ArrayList<>(sorted.size());
        for (DocumentNote n : sorted) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("noteId", n.getId());
            row.put("text", n.getText());
            row.put("done", n.isDone());
            if (n.getLine() != null) row.put("line", n.getLine());
            row.put("userId", n.getUserId());
            if (n.getCreatedAt() != null) row.put("createdAt", n.getCreatedAt().toString());
            if (n.getUpdatedAt() != null) row.put("updatedAt", n.getUpdatedAt().toString());
            out.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", doc.getId());
        result.put("path", doc.getPath());
        result.put("count", out.size());
        result.put("notes", out);
        return result;
    }
}
