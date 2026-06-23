package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.api.common.AccentColor;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocSetColorTool implements Tool {

    private static final String PALETTE = Arrays.stream(AccentColor.values())
            .map(c -> c.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(", "));

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("color"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("color", Map.of(
                "type", "string",
                "description", "Accent color from the 12-value palette ("
                        + PALETTE + "). Pass '' or 'none' to clear."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_set_color"; }
    @Override public String description() {
        return "Set or clear the accent color of a document. Color palette is "
                + "case-insensitive; pass an empty string or 'none' to clear.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("color", "eddie", "write", "document"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.loadDocument(params, ctx);
        // Raw read — requireString would refuse a blank "clear" value.
        String raw = KindToolSupport.paramRawString(params, "color");
        if (raw == null) {
            throw new ToolException("Missing required parameter 'color'");
        }
        @Nullable AccentColor color = parseColor(raw);
        if (color == null) {
            support.documentService().clearColor(doc.getId());
        } else {
            support.documentService().setColor(doc.getId(), color);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("color", color == null ? null : color.name());
        return out;
    }

    private static @Nullable AccentColor parseColor(String raw) {
        String t = raw.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("none") || t.equalsIgnoreCase("null")) {
            return null;
        }
        try {
            return AccentColor.valueOf(t.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException(
                    "Unknown color '" + raw + "'. Allowed: " + PALETTE + " — or '' / 'none' to clear.");
        }
    }
}
