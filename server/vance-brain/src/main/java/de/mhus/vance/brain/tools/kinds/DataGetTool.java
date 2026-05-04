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
public class DataGetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("path", Map.of("type", "string",
                "description", "JSON Pointer (RFC-6901). Empty/'/' returns the whole body. "
                        + "Examples: '/users/0/email', '/config/timeout'."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "data_get"; }
    @Override public String description() {
        return "Read a value from a `kind: data` document at the given JSON Pointer path. "
                + "Empty path returns the whole body.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-data", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "data");
        String pathStr = KindToolSupport.paramString(params, "path");
        String[] path = JsonPointer.parse(pathStr);
        DataDocument data = DataCodec.parse(doc.getInlineText(), doc.getMimeType());
        Object value = path.length == 0 ? data.body() : JsonPointer.resolve(data.body(), path);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", JsonPointer.format(path));
        out.put("value", value);
        out.put("found", value != null || (path.length == 0));
        return out;
    }
}
