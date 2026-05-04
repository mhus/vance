package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.DataCodec;
import de.mhus.vance.shared.document.kind.DataDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("path"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("path", Map.of("type", "string",
                "description", "JSON Pointer (RFC-6901) of the value to delete."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "data_delete"; }
    @Override public String description() {
        return "Delete a value at a JSON Pointer in a `kind: data` document. "
                + "Removing an array element shifts subsequent indices.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-data", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "data");
        String pathStr = KindToolSupport.requireString(params, "path");
        String[] path = JsonPointer.parse(pathStr);

        DataDocument data = DataCodec.parse(doc.getInlineText(), doc.getMimeType());
        Object body = JsonPointer.mutableCopy(data.body());
        if (body == null) body = new LinkedHashMap<String, Object>();
        Object removed = JsonPointer.remove(body, path);
        DataDocument updated = new DataDocument(data.kind(), body, data.meta());
        support.writeBody(doc, DataCodec.serialize(updated, doc.getMimeType()), ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", JsonPointer.format(path));
        out.put("removed", removed != null);
        out.put("removedValue", removed);
        return out;
    }
}
