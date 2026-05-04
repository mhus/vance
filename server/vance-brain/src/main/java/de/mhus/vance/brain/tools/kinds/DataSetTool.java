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
public class DataSetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("path", "value"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("path", Map.of("type", "string",
                "description", "JSON Pointer (RFC-6901) to the target. Use '-' as the last "
                        + "segment to append to a list. Intermediate Objects are auto-created."));
        p.put("value", Map.of("description",
                "Value to write — any JSON-compatible type (string, number, boolean, null, "
                        + "object, array)."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "data_set"; }
    @Override public String description() {
        return "Set a value in a `kind: data` document at the given JSON Pointer. "
                + "Creates intermediate objects on demand. Use '-' as the last token to append to an array.";
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
        Object value = params == null ? null : params.get("value");

        DataDocument data = DataCodec.parse(doc.getInlineText(), doc.getMimeType());
        Object body = JsonPointer.mutableCopy(data.body());
        if (body == null) body = new LinkedHashMap<String, Object>();
        Object[] holder = new Object[]{body};
        Object previous = JsonPointer.set(body, path, value, (newRoot) -> holder[0] = newRoot);
        DataDocument updated = new DataDocument(data.kind(), holder[0], data.meta());
        support.writeBody(doc, DataCodec.serialize(updated, doc.getMimeType()), ctx);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", JsonPointer.format(path));
        out.put("previousValue", previous);
        out.put("newValue", value);
        return out;
    }
}
