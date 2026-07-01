package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.client.CortexTurnSelectionHolder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Read the text the user has selected in the chat-bound Cortex document.
 *
 * <p>The selection <em>range</em> rides with each steer
 * ({@code ProcessSteerRequest.boundDocSelection}); the content itself is
 * never inlined into the prompt. This server-side tool lets the model
 * pull the selected text on demand — cheap for the common case, safe for
 * huge selections (paged via {@code head}/{@code tail}).
 *
 * <ul>
 *   <li><b>No args</b> — use the selection that came with the current
 *       message (via {@link CortexTurnSelectionHolder}). Errors when the
 *       turn carried none.</li>
 *   <li><b>{@code path}/{@code id} + {@code from} + {@code to}</b> — read
 *       an explicit character range from any accessible document.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DocGetSelectionTool implements Tool {

    private static final int MAX_CHARS = 32 * 1024;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("from", Map.of("type", "integer",
                "description", "0-based start character offset. Omit (with `to`) to use "
                        + "the selection that arrived with the current message."));
        p.put("to", Map.of("type", "integer",
                "description", "End character offset (exclusive). Omit to use the "
                        + "current message's selection."));
        p.put("head", Map.of("type", "integer",
                "description", "Return only the first N characters of the selection."));
        p.put("tail", Map.of("type", "integer",
                "description", "Return only the last N characters of the selection."));
        return p;
    }

    private final KindToolSupport support;
    private final CortexTurnSelectionHolder selectionHolder;

    @Override public String name() { return "doc_get_selection"; }

    @Override public String description() {
        return "Read the text the user has selected in the chat-bound Cortex document. "
                + "Call with NO arguments to read the selection that came with the current "
                + "message — that's what the user means by \"the selected part\" / \"diesen "
                + "Teil\". Or pass `path`/`id` + `from` + `to` for an explicit character "
                + "range. Use `head`/`tail` to page a large selection. Errors if called "
                + "with no args when no selection was sent this turn.";
    }

    @Override public boolean primary() { return true; }

    @Override public Set<String> labels() {
        return Set.of("text-search", "read-only", "eddie", "cortex");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Integer from = KindToolSupport.paramInt(params, "from");
        Integer to = KindToolSupport.paramInt(params, "to");
        String id = KindToolSupport.paramString(params, "id");
        String path = KindToolSupport.paramString(params, "path");

        DocumentDocument doc;
        if (from == null || to == null || (id == null && path == null)) {
            // No explicit range → use the selection that rode in with this turn.
            CortexTurnSelectionHolder.Selection sel = selectionHolder.get(ctx.processId());
            if (sel == null) {
                throw new ToolException("No selection available: nothing was selected in the "
                        + "bound document when this message was sent. Ask the user to select "
                        + "text, or pass path/id + from + to explicitly.");
            }
            doc = support.loadDocument(Map.of("id", sel.documentId()), ctx);
            from = sel.from();
            to = sel.to();
        } else {
            doc = support.loadDocument(params, ctx);
        }

        String body = support.readBody(doc, ctx);
        int len = body.length();
        int f = Math.max(0, Math.min(from, len));
        int t = Math.max(f, Math.min(to, len));
        String selected = body.substring(f, t);

        Integer head = KindToolSupport.paramInt(params, "head");
        Integer tail = KindToolSupport.paramInt(params, "tail");
        boolean truncated = false;
        if (head != null && head >= 0 && selected.length() > head) {
            selected = selected.substring(0, head);
            truncated = true;
        } else if (tail != null && tail >= 0 && selected.length() > tail) {
            selected = selected.substring(selected.length() - tail);
            truncated = true;
        } else if (selected.length() > MAX_CHARS) {
            selected = selected.substring(0, MAX_CHARS);
            truncated = true;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        out.put("from", f);
        out.put("to", t);
        out.put("length", t - f);
        out.put("truncated", truncated);
        out.put("text", selected);
        return out;
    }
}
