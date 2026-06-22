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
 * Replace the entire body of an existing document with new content —
 * full overwrite. The document must already exist; use {@code doc_create}
 * to create a fresh one.
 *
 * <p>Symmetry note: {@code doc_edit} for surgical find-and-replace,
 * {@code doc_replace_lines} for line-range patches, {@code doc_write} for
 * complete content replacement. Keep the smallest tool that fits — full
 * overwrite is the bluntest option and easiest to get wrong on a doc the
 * user is actively viewing.
 */
@Component
@RequiredArgsConstructor
public class DocWriteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("content"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("content", Map.of("type", "string",
                "description", "The new full body of the document. Replaces whatever was there before."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_write"; }
    @Override public String description() {
        return "Replace the entire body of an existing document with new content. The document must "
                + "already exist — use doc_create to create a new one. Prefer doc_edit or "
                + "doc_replace_lines when only a portion needs to change; full overwrite is the "
                + "bluntest option.";
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
        support.writeBody(doc, content, ctx);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        out.put("newLength", content.length());
        return out;
    }
}
