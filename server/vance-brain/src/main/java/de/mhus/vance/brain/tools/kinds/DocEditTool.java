package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Patch a document by replacing an exact substring — same model as
 * Claude Code's {@code Edit} tool. Lets the LLM modify a document
 * by quoting a small unique chunk instead of round-tripping the
 * whole body.
 *
 * <p>Failure modes mirror Claude's:
 * <ul>
 *   <li>{@code old_string} not found → error.</li>
 *   <li>{@code old_string} appears more than once and {@code replace_all}
 *       is false → error (caller must add more context to make it unique).</li>
 *   <li>{@code old_string == new_string} → error (no-op edits are bugs).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DocEditTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("old_string", "new_string"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("old_string", Map.of("type", "string",
                "description", "The text to replace. Must be unique within the document, "
                        + "or set replace_all=true."));
        p.put("new_string", Map.of("type", "string",
                "description", "The replacement text (must differ from old_string)."));
        p.put("replace_all", Map.of("type", "boolean",
                "description", "Replace every occurrence of old_string. Default: false."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_edit"; }
    @Override public String description() {
        return "Patch an inline document by replacing an exact substring. The match must be unique "
                + "unless replace_all=true. Use the smallest old_string that uniquely identifies "
                + "the target — avoid pasting 10+ lines when 2-4 are enough.";
    }
    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireInline(support.loadDocument(params, ctx));
        String oldString = KindToolSupport.requireRawString(params, "old_string");
        String newString = KindToolSupport.requireRawString(params, "new_string");
        boolean replaceAll = Boolean.TRUE.equals(KindToolSupport.paramBoolean(params, "replace_all"));

        if (oldString.equals(newString)) {
            throw new ToolException("old_string and new_string are identical — nothing to do");
        }
        if (oldString.isEmpty()) {
            throw new ToolException("old_string must be non-empty");
        }
        String body = doc.getInlineText();
        int occurrences = countOccurrences(body, oldString);
        if (occurrences == 0) {
            throw new ToolException("old_string not found in document " + support.identify(doc));
        }
        if (occurrences > 1 && !replaceAll) {
            throw new ToolException("old_string appears " + occurrences + " times in document "
                    + support.identify(doc) + " — add more surrounding context to make it unique, "
                    + "or set replace_all=true.");
        }

        String updated = replaceAll
                ? body.replace(oldString, newString)
                : replaceFirst(body, oldString, newString);
        support.writeBody(doc, updated, ctx);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        out.put("replacedOccurrences", occurrences);
        out.put("replaceAll", replaceAll);
        return out;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) return count;
            count++;
            from = idx + needle.length();
        }
    }

    private static String replaceFirst(String haystack, String needle, String replacement) {
        int idx = haystack.indexOf(needle);
        if (idx < 0) return haystack;
        return haystack.substring(0, idx) + replacement + haystack.substring(idx + needle.length());
    }
}
