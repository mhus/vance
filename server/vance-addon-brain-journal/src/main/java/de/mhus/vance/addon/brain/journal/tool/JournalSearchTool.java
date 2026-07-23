package de.mhus.vance.addon.brain.journal.tool;

import de.mhus.vance.addon.brain.journal.JournalConfig;
import de.mhus.vance.addon.brain.journal.JournalFolderReader;
import de.mhus.vance.addon.brain.journal.JournalService;
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
 * Free-text search over journal entries — matches entry title, the
 * LLM-written summary and tags (the body itself is a compressed blob and
 * not searchable). Optional mood + tag facets. Delegates to the shared
 * {@link DocumentService#searchProjectDocumentsMeta}.
 */
@Component
@Slf4j
public class JournalSearchTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "The journal root folder (contains _app.yaml)."));
                put("query", Map.of("type", "string",
                        "description", "Free text matched against title / summary / tags."));
                put("mood", Map.of("type", "string"));
                put("tag", Map.of("type", "string"));
                put("limit", Map.of("type", "integer", "description", "Max hits (default 20)."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final JournalFolderReader folderReader;
    private final JournalService journalService;

    public JournalSearchTool(EddieContext eddieContext,
                             JournalFolderReader folderReader,
                             JournalService journalService) {
        this.eddieContext = eddieContext;
        this.folderReader = folderReader;
        this.journalService = journalService;
    }

    @Override public String name() { return "journal_search"; }

    @Override
    public String description() {
        return "Search past journal entries by free text (title / summary / tags), "
                + "optionally filtered by mood or a tag. The prose body is not directly "
                + "searchable — hits rank on the entry's summary. Returns date + title + "
                + "snippet per hit.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "read", "document", "journal", "search");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        int limit = paramInt(params, "limit", 20);

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        JournalConfig config = folderReader.scan(
                ctx.tenantId(), project.getName(), folder).config();

        DocumentService.DocumentMetaListing listing = journalService.search(
                ctx.tenantId(), project.getName(), folder, config,
                paramString(params, "query"), paramString(params, "mood"),
                paramString(params, "tag"), limit);

        List<Map<String, Object>> hits = new ArrayList<>();
        for (DocumentService.DocumentMetaMatch m : listing.items()) {
            String leaf = m.path().substring(m.path().lastIndexOf('/') + 1);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("date", JournalFolderReader.dateFromLeaf(leaf));
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
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static int paramInt(Map<String, Object> params, String key, int fallback) {
        if (params == null) return fallback;
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { /* keep */ }
        }
        return fallback;
    }
}
