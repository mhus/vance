package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocAddTagTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("tag"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("tag", Map.of("type", "string", "description", "Tag to add (no-op if already present)."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_add_tag"; }
    @Override public String description() {
        return "Add a tag to a document. No-op when the tag is already present.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("tags", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.loadDocument(params, ctx);
        String tag = KindToolSupport.requireString(params, "tag");
        List<String> tags = doc.getTags() == null
                ? new ArrayList<>()
                : new ArrayList<>(doc.getTags());
        boolean added = !tags.contains(tag);
        if (added) tags.add(tag);
        // Flush body buffer first so update() doesn't overwrite the
        // in-flight body when it writes the new tag set.
        support.buffer().flush(ctx.processId(), doc.getId());
        DocumentDocument saved = support.documentService().update(
                doc.getId(), null, tags, null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", saved.getId());
        out.put("tag", tag);
        out.put("added", added);
        out.put("tags", saved.getTags());
        return out;
    }
}
