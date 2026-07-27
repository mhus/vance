package de.mhus.vance.brain.tools.foreign;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentService.DocumentMetaListing;
import de.mhus.vance.shared.document.DocumentService.DocumentMetaMatch;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Search another project's documents by metadata — title, LLM-written summary
 * and tags — returning ranked hits with a snippet. Requires READ on the target
 * project. This is a metadata/path search (NOT semantic RAG): it finds "which
 * documents in project X are about Y" so the caller can read or copy them.
 */
@Component
@RequiredArgsConstructor
public class ForeignDocSearchTool implements Tool {

    private static final int DEFAULT_LIMIT = 25;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of("type", "string",
                            "description", "Name of the project to search (required)."),
                    "query", Map.of("type", "string",
                            "description", "Free-text needle matched against title, summary and tags."),
                    "limit", Map.of("type", "integer",
                            "description", "Max hits (default " + DEFAULT_LIMIT + ", capped at 200).")),
            "required", List.of("projectId", "query"));

    private final ForeignAccessSupport foreign;

    @Override public String name() { return "foreign_doc_search"; }

    @Override public String description() {
        return "Search ANOTHER project's documents by title/summary/tags and get ranked hits with "
                + "snippets. Read-only; requires read access to that project. Metadata search, not "
                + "semantic RAG. Follow up with foreign_doc_read or foreign_doc_copy on a hit.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return false; }
    @Override public Set<String> labels() { return Set.of("read-only", "cross-project", "document"); }
    @Override public String searchHint() { return "Search documents in another project by topic"; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String projectId = KindToolSupport.requireString(params, "projectId");
        String query = KindToolSupport.requireString(params, "query");
        Integer limitParam = KindToolSupport.paramInt(params, "limit");
        int limit = limitParam != null ? limitParam : DEFAULT_LIMIT;
        ProjectDocument project = foreign.resolveForeign(projectId, ctx, Action.READ);

        DocumentMetaListing listing = foreign.documents().searchProjectDocumentsMeta(
                ctx.tenantId(), project.getName(), null, query, null, null, limit);

        List<Map<String, Object>> hits = new ArrayList<>();
        for (DocumentMetaMatch m : listing.items()) {
            if (ForeignAccessSupport.reserved(m.path())) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", m.id());
            r.put("path", m.path());
            if (m.title() != null) r.put("title", m.title());
            if (m.kind() != null) r.put("kind", m.kind());
            if (m.mimeType() != null) r.put("mimeType", m.mimeType());
            r.put("snippet", m.snippet());
            r.put("score", m.score());
            hits.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        out.put("query", query);
        out.put("count", hits.size());
        out.put("total", listing.total());
        out.put("results", hits);
        return out;
    }
}
