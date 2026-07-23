package de.mhus.vance.addon.brain.gtd.tool;

import de.mhus.vance.addon.brain.gtd.GtdFolderReader;
import de.mhus.vance.addon.brain.gtd.GtdService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Free-text search over GTD actions — title + summary + tags (contexts). The
 * body is a compressed blob and not directly searchable. Delegates to the
 * shared {@link DocumentService#searchProjectDocumentsMeta}.
 */
@Component
@Slf4j
public class GtdSearchTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string", "description", "GTD root folder."));
                put("query", Map.of("type", "string"));
                put("context", Map.of("type", "string"));
                put("limit", Map.of("type", "integer", "description", "Max hits (default 20)."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final GtdFolderReader folderReader;
    private final GtdService gtdService;

    public GtdSearchTool(EddieContext eddieContext, GtdFolderReader folderReader, GtdService gtdService) {
        this.eddieContext = eddieContext;
        this.folderReader = folderReader;
        this.gtdService = gtdService;
    }

    @Override public String name() { return "gtd_search"; }

    @Override
    public String description() {
        return "Search GTD actions by free text (title / summary / contexts), optionally "
                + "filtered by a context. The body is not directly searchable — hits rank on "
                + "the action's summary. Returns title + snippet per hit.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("eddie", "read", "document", "gtd", "search"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        int limit = paramInt(params, "limit", 20);
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        // ensure the folder is a GTD app (throws otherwise)
        folderReader.scan(ctx.tenantId(), project.getName(), folder);
        DocumentService.DocumentMetaListing listing = gtdService.search(
                ctx.tenantId(), project.getName(), folder,
                paramString(params, "query"), paramString(params, "context"), limit);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (DocumentService.DocumentMetaMatch m : listing.items()) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("title", m.title());
            h.put("path", m.path());
            h.put("snippet", m.snippet());
            hits.add(h);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", listing.total());
        result.put("hits", hits);
        return result;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
    private static int paramInt(Map<String, Object> params, String key, int fallback) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { /* keep */ }
        }
        return fallback;
    }
}
