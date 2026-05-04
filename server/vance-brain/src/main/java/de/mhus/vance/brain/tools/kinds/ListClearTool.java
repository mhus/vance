package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.ListCodec;
import de.mhus.vance.shared.document.kind.ListDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Remove all items from a {@code kind: list} document. */
@Component
@RequiredArgsConstructor
public class ListClearTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "list_clear"; }

    @Override public String description() {
        return "Remove all items from a `kind: list` document. The document and its front-matter remain.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-list", "eddie"); }


    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "list");
        ListDocument list = ListCodec.parse(doc.getInlineText(), doc.getMimeType());
        int previous = list.items().size();
        ListDocument cleared = new ListDocument(list.kind(), new ArrayList<>(), list.extra());
        support.writeBody(doc, ListCodec.serialize(cleared, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(), "removedCount", previous);
    }
}
