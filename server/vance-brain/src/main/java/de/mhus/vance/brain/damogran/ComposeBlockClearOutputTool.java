package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.compose.ComposeBlockCodec;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The {@code compose_block_clear_output} tool — drops the managed
 * {@code $output:}/{@code $run:} blocks from a compose document (addressed by
 * {@code id} or {@code path}), leaving the hand-authored manifest untouched.
 * The write goes through the same buffer/identity path the {@code doc_*} tools
 * use, so an open editor updates live. Counterpart of the browser's
 * "Clear Output" button.
 */
@Component
public class ComposeBlockClearOutputTool implements Tool {

    private final KindToolSupport support;

    public ComposeBlockClearOutputTool(KindToolSupport support) {
        this.support = support;
    }

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    @Override
    public String name() {
        return "compose_block_clear_output";
    }

    @Override
    public String description() {
        return "Clear the shown output of a compose document (addressed by id or path): "
                + "removes the managed $output:/$run: blocks while keeping the hand-authored "
                + "manifest intact. An open editor updates live. Use this to reset a compose "
                + "block's output.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("write", "document");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.loadDocument(params, ctx);
        String current = support.readBody(doc, ctx);
        String updated = ComposeBlockCodec.clearComposeManaged(current);
        boolean cleared = !updated.equals(current);
        if (cleared) {
            support.writeBody(doc, updated, ctx);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        out.put("cleared", cleared);
        return out;
    }
}
