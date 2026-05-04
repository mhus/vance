package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Copy a document to a new path inside the same project. The
 * source's pending buffered changes (if any) are flushed first so
 * the copy reflects the latest in-flight state, not stale disk
 * content.
 *
 * <p>Cross-project copy is a separate {@code cross_doc_copy} tool —
 * different blast radius, different default availability.
 */
@Component
@RequiredArgsConstructor
public class DocCopyTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("newPath"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("newPath", Map.of("type", "string",
                "description", "New path inside the source's project. Must not exist yet."));
        p.put("title", Map.of("type", "string",
                "description", "Optional new title for the copy. Defaults to the source's title."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_copy"; }
    @Override public String description() {
        return "Copy a document to a new path within the same project. The source remains; the "
                + "copy gets its own id. Pending in-flight changes on the source are flushed first.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("doc-management", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument source = support.loadDocument(params, ctx);
        support.requireInline(source);
        support.buffer().flush(ctx.processId(), source.getId());
        DocumentDocument fresh = support.buffer().read(ctx.processId(), source.getId());
        if (fresh == null) throw new ToolException("Source document disappeared during copy");
        String newPath = KindToolSupport.requireString(params, "newPath");
        String title = KindToolSupport.paramString(params, "title");
        DocumentDocument copy;
        try {
            copy = support.documentService().create(
                    fresh.getTenantId(),
                    fresh.getProjectId(),
                    newPath,
                    title != null ? title : fresh.getTitle(),
                    fresh.getTags() != null ? List.copyOf(fresh.getTags()) : null,
                    fresh.getMimeType(),
                    new ByteArrayInputStream(fresh.getInlineText().getBytes(StandardCharsets.UTF_8)),
                    ctx.userId());
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ToolException(e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceId", fresh.getId());
        out.put("sourcePath", fresh.getPath());
        out.put("newId", copy.getId());
        out.put("newPath", copy.getPath());
        if (copy.getKind() != null) out.put("kind", copy.getKind());
        return out;
    }
}
