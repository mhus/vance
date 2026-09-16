package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.document.AgeDocumentGuard;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.ContentHashes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>Optional If-Match guard: pass the {@code contentHash} from the last
 * read as {@code expectedContentHash} and the edit is refused when the
 * document changed meanwhile — the doc-side twin of the file_* protocol
 * (work-target.md). The result carries the new {@code contentHash} so
 * consecutive edits can chain without re-reading.
 */
@Component
@RequiredArgsConstructor
public class DocEditTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("oldText", "newText"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put(
                "oldText",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "The text to replace. Must be unique within the document, " + "or set replace_all=true."));
        p.put("newText", Map.of("type", "string", "description", "The replacement text (must differ from oldText)."));
        p.put(
                "replaceAll",
                Map.of("type", "boolean", "description", "Replace every occurrence of oldText. Default: false."));
        p.put("expectedContentHash", KindToolSupport.expectedContentHashProperty());
        return p;
    }

    private final KindToolSupport support;

    @Override
    public String name() {
        return "doc_edit";
    }

    @Override
    public String description() {
        return "Patch an inline document by replacing an exact substring. The match must be unique "
                + "unless replace_all=true. Use the smallest old_string that uniquely identifies "
                + "the target — avoid pasting 10+ lines when 2-4 are enough. Pass the contentHash "
                + "from your last read as expectedContentHash to refuse the edit when the "
                + "document changed since that read.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Set<String> labels() {
        return Set.of("text-edit", "eddie", "write", "document");
    }

    @Override
    public Set<String> prakLabels() {
        return Set.of("knowledge", "documents");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireInline(support.loadDocument(params, ctx));
        AgeDocumentGuard.requireWritable(doc);
        // camelCase like file_edit and the rest of the tool surface; this one
        // shipped in snake_case, so the same operation had two spellings.
        String oldString = KindToolSupport.requireRawStringAliased(params, "oldText", "old_string");
        String newString = KindToolSupport.requireRawStringAliased(params, "newText", "new_string");
        boolean replaceAll =
                Boolean.TRUE.equals(KindToolSupport.paramBooleanAliased(params, "replaceAll", "replace_all"));

        if (oldString.equals(newString)) {
            throw new ToolException("old_string and new_string are identical — nothing to do");
        }
        if (oldString.isEmpty()) {
            throw new ToolException("old_string must be non-empty");
        }
        String body = support.readBody(doc, ctx);
        // If-Match guard first: a stale read usually breaks the snippet
        // match too, and the model should get the "read again" advice
        // instead of "expand your snippet context" (work-target.md).
        KindToolSupport.enforceContentHashMatch(params, doc, body);
        int occurrences = countOccurrences(body, oldString);
        if (occurrences == 0) {
            throw new ToolException("old_string not found in document " + support.identify(doc));
        }
        if (occurrences > 1 && !replaceAll) {
            throw new ToolException("old_string appears " + occurrences + " times in document "
                    + support.identify(doc) + " — add more surrounding context to make it unique, "
                    + "or set replace_all=true.");
        }

        String updated = replaceAll ? body.replace(oldString, newString) : replaceFirst(body, oldString, newString);
        Map<String, Object> validation = support.writeBody(doc, updated, ctx);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        out.put("replacedOccurrences", occurrences);
        out.put("replaceAll", replaceAll);
        // Chainable: the caller can pass this straight back as
        // expectedContentHash on the next edit without re-reading.
        out.put("contentHash", ContentHashes.sha256Hex(updated));
        if (validation != null) out.put("validation", validation);
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
