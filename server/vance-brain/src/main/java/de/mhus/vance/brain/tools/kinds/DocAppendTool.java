package de.mhus.vance.brain.tools.kinds;

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
 * Append content to the end of an existing document. Inserts a single
 * newline separator between the old body and the new content when the
 * old body is non-empty and doesn't already end with a newline, so
 * markdown / log-style documents stay correctly delimited.
 *
 * <p>Use this when the document is a chronological log (notes, journal,
 * audit trail). For structured updates inside the body, prefer
 * {@code doc_edit} or {@code doc_replace_lines}.
 */
@Component
@RequiredArgsConstructor
public class DocAppendTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("content"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("content", Map.of("type", "string",
                "description", "The text to append at the end of the document. A single newline is "
                        + "auto-inserted between the existing body and the new content when needed."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_append"; }
    @Override public String description() {
        return "Append content to the end of an existing document. Auto-inserts a single newline "
                + "separator when the body doesn't already end with one. Use for chronological logs "
                + "(notes, journals); for structured edits prefer doc_edit or doc_replace_lines.";
    }
    @Override public boolean primary() { return true; }
    @Override public Set<String> labels() { return Set.of("text-edit", "eddie", "write", "document"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireInline(support.loadDocument(params, ctx));
        String content = KindToolSupport.paramRawString(params, "content");
        if (content == null) {
            throw new ToolException("Missing required parameter 'content'");
        }
        if (content.isEmpty()) {
            throw new ToolException("content must be non-empty");
        }
        String existing = support.readBody(doc, ctx);
        StringBuilder out = new StringBuilder(existing.length() + content.length() + 1);
        out.append(existing);
        if (!existing.isEmpty() && !existing.endsWith("\n")) {
            out.append('\n');
        }
        out.append(content);
        String updated = out.toString();
        support.writeBody(doc, updated, ctx);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", doc.getId());
        result.put("path", doc.getPath());
        result.put("appendedLength", content.length());
        result.put("newLength", updated.length());
        return result;
    }
}
